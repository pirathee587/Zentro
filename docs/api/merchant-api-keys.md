# Merchant API Key Management (ZD-27)

Service: S1 Identity & Partner (`identity-partner-service`, port 8081), reached through the API gateway on port 8080.  
Issue: #62. Related: #23 (Merchant API), #27 (Merchant Approval), #67 (Merchant API Validation).

---

## 1. Overview & Security Architecture

The Merchant API Key Management module provides secure issuance, rotation, revocation, and lifecycle administration for merchant credentials.

### Credential Design & Cryptographic Standards
- **Secret format**: `zn_live_<32 cryptographically secure random bytes base64url>` (51 characters total, starting with `zn_live_`). Generated using `java.security.SecureRandom`.
- **Prefix format**: `zn_live_<first 8 base64url characters>` (16 characters total). Used for identification and display in user portals.
- **One-time reveal**: The full secret is returned **only once** upon generation or rotation in the HTTP response of `POST /api/v1/merchants/api-key`. It is **never logged**, transmitted in metrics, or shown in error messages.
- **Secure representation storage**: The database table `identity.merchant_api_key` never stores plaintext credentials. It stores only:
  - `key_prefix`: `VARCHAR(32)` unique prefix with check constraint `CHECK (key_prefix LIKE 'zn_live_%')`.
  - `key_hash`: `CHAR(64)` storing the raw cryptographic SHA-256 digest in lowercase hexadecimal.
  - Indexed via `idx_merchant_api_key_hash` for fast verification lookups.
- **Scope & Ownership**: All merchant operations derive caller identity strictly from the verified Keycloak JWT `sub` claim. Merchants cannot access, view, or revoke credentials belonging to another merchant.
- **Access eligibility**: Issuance, rotation, and retrieval strictly require that the merchant's `app_user.account_status` is `ACTIVE` and their `merchant_application.status` is `APPROVED`.

---

## 2. Merchant Self-Service Endpoints

Base path: `/api/v1/merchants/api-key`  
Gateway route: `/api/v1/merchants/**` (requires human authentication; S1 enforces `@PreAuthorize("hasRole('MERCHANT')")`).

### 2.1 View Current Key Metadata
`GET /api/v1/merchants/api-key`

| Item | Value |
|---|---|
| Role | `MERCHANT` only |
| Precondition | Approved merchant application (`APPROVED`) and active user account (`ACTIVE`) |
| Auth header | `Authorization: Bearer <Keycloak access token>` |
| Response | `200 OK` with `MerchantApiKeyMetadata`, or `200 OK` with null body if no key exists |

#### Response Example (`200 OK`)
```json
{
  "id": "7f0a9967-1724-4f81-9b16-5bc456a12345",
  "prefix": "zn_live_a1b2c3d4",
  "createdAt": "2026-11-01T08:30:00Z",
  "rotatedAt": "2026-11-05T14:15:00Z",
  "revokedAt": null,
  "active": true
}
```

---

### 2.2 Issue or Rotate API Key
`POST /api/v1/merchants/api-key`

| Item | Value |
|---|---|
| Role | `MERCHANT` only |
| Precondition | Approved merchant application (`APPROVED`) and active user account (`ACTIVE`) |
| Action | Atomically creates a new key or rotates an existing key. Clears `revoked_at`, updates `rotated_at = now()`, and preserves original `created_at`. Previous key is invalidated immediately. |
| Response | `200 OK` with `IssuedMerchantApiKey` |

#### Response Example (`200 OK`)
```json
{
  "secret": "zn_live_K7z9X1v2B8c3D4e5F6g7H8j9K0l1M2n3P4q5R6s7T8u",
  "credential": {
    "id": "7f0a9967-1724-4f81-9b16-5bc456a12345",
    "prefix": "zn_live_K7z9X1v2",
    "createdAt": "2026-11-01T08:30:00Z",
    "rotatedAt": "2026-11-07T10:00:00Z",
    "revokedAt": null,
    "active": true
  }
}
```

> **Security Notice**: This is the only response that includes the `secret` field. Integrations must store this value immediately in a secure vault or environment variable.

---

### 2.3 Revoke API Key
`DELETE /api/v1/merchants/api-key`

| Item | Value |
|---|---|
| Role | `MERCHANT` only |
| Precondition | Approved merchant application (`APPROVED`) and active user account (`ACTIVE`) |
| Action | Sets `revoked_at = COALESCE(revoked_at, now())`. Revocation takes effect immediately. |
| Response | `200 OK` with `MerchantApiKeyMetadata` (`active: false`) |

#### Response Example (`200 OK`)
```json
{
  "id": "7f0a9967-1724-4f81-9b16-5bc456a12345",
  "prefix": "zn_live_K7z9X1v2",
  "createdAt": "2026-11-01T08:30:00Z",
  "rotatedAt": "2026-11-07T10:00:00Z",
  "revokedAt": "2026-11-07T11:00:00Z",
  "active": false
}
```

---

## 3. Administrative (Dispatcher) Oversight Endpoints

Base path: `/api/v1/admin/merchants/{applicationId}/api-key`  
Gateway route: `/api/v1/admin/**` (strictly restricted to `ROLE_DISPATCHER`).

### 3.1 View Merchant Key Metadata (Admin)
`GET /api/v1/admin/merchants/{applicationId}/api-key`

| Item | Value |
|---|---|
| Role | `DISPATCHER` only (`@PreAuthorize("hasRole('DISPATCHER')")`) |
| Precondition | Target merchant application exists and has an associated key |
| Response | `200 OK` with `MerchantApiKeyMetadata` (never reveals the secret) |
| Errors | `404 Not Found` if application or key does not exist |

---

### 3.2 Revoke Merchant Key (Admin)
`DELETE /api/v1/admin/merchants/{applicationId}/api-key`

| Item | Value |
|---|---|
| Role | `DISPATCHER` only (`@PreAuthorize("hasRole('DISPATCHER')")`) |
| Precondition | Target merchant application exists and has an associated key |
| Action | Immediately marks the key revoked (`revoked_at = now()`), disabling any active integrations |
| Response | `200 OK` with `MerchantApiKeyMetadata` (`active: false`) |
| Errors | `404 Not Found` if application or key does not exist |

---

## 4. Key Validation API Contract (`validateKey`)

For internal service integration (e.g., S2 Parcel & Pricing or gateway authentication):

```java
public Optional<ValidatedMerchantApiKey> validateKey(String rawSecret);
```

```java
public record ValidatedMerchantApiKey(
    UUID id,
    UUID appUserId,
    String prefix,
    boolean active);
```

### Validation Rules
1. Format check: `rawSecret` must not be null, must begin with `zn_live_`, and must meet minimum length requirements.
2. Hash calculation: Computes SHA-256 digest of `rawSecret`.
3. Database verification: Performs parameterized query against `identity.merchant_api_key`:
   ```sql
   SELECT k.id, k.app_user_id, k.key_prefix,
          (k.revoked_at IS NULL AND u.account_status = 'ACTIVE' AND m.status = 'APPROVED') AS is_active
     FROM identity.merchant_api_key k
     JOIN identity.app_user u ON u.id = k.app_user_id
     JOIN identity.merchant_application m ON m.app_user_id = u.id
    WHERE k.key_hash = ?
   ```
4. Returns:
   - `Optional.empty()` if hash does not exist.
   - `Optional.of(ValidatedMerchantApiKey)` with `active = true` if key is unrevoked, user is ACTIVE, and merchant is APPROVED.
   - `Optional.of(ValidatedMerchantApiKey)` with `active = false` if key is revoked, account is suspended, or application is not approved.

---

## 5. Security & Error Matrix

| Scenario | HTTP Status | Response / Consequence |
|---|---|---|
| Unauthenticated caller | `401 Unauthorized` | Rejected before reaching controller |
| Non-merchant calls `/api/v1/merchants/api-key` | `403 Forbidden` | Access denied |
| Non-dispatcher calls `/api/v1/admin/merchants/**` | `403 Forbidden` | Access denied |
| Unapproved merchant attempts key action | `403 Forbidden` | `API keys are available to approved, active merchants only.` |
| Suspended merchant account attempts key action | `403 Forbidden` | `API keys are available to approved, active merchants only.` |
| Revoke non-existent key | `404 Not Found` | `No API key exists for this merchant.` |
| Admin query for non-existent application | `404 Not Found` | `Merchant application was not found.` |
| Prefix collision on creation | Handled internally | Cryptographic retry on collision |
