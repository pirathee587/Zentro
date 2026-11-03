# Merchant Order API (ZD-08)

Service: S2 Parcel & Pricing (`parcel-pricing-service`, port 8082), reached through the API gateway on port 8080.  
Issue: #23. Related: #17 (Merchant Registration), #27 (Merchant Approval), #62 (API Keys), #32 (Webhooks).

## 1. Endpoints

### 1.1 Create or Replay Merchant Order
`POST /api/v1/merchant/orders`

| Item | Value |
|---|---|
| Role | `MERCHANT` only (`@PreAuthorize("hasRole('MERCHANT')")`) |
| Approval | Requires `APPROVED` merchant application and `ACTIVE` account status verified via Identity (S1) |
| Auth | `Authorization: Bearer <Keycloak access token>` |
| Content type | `application/json` |
| Idempotency | Determined by `externalOrderRef` (scoped per merchant) |
| Order owner | Strictly resolved from the token `sub` claim. Client cannot forge or override `merchantId`. |

The gateway routes `/api/v1/merchant/orders/**` to S2 and restricts `/api/v1/merchant/**` to `ROLE_MERCHANT`. S2 enforces local role checks and actively verifies merchant approval with S1.

### 1.2 Get Order by ID
`GET /api/v1/merchant/orders/{id}`

Returns the merchant order matching `{id}`. Requests are strictly scoped to the authenticated merchant; querying another merchant's order ID returns `404 Not Found` to prevent resource enumeration.

### 1.3 List Merchant Orders
`GET /api/v1/merchant/orders?limit={limit}`

Returns recent orders placed by the authenticated merchant, ordered by `createdAt DESC`. Default limit is 50 (maximum 100).

---

## 2. Request Contract (`POST /api/v1/merchant/orders`)

```json
{
  "externalOrderRef": "ORD-2026-11-9876",
  "sender": {
    "name": "Apex Electronics",
    "phone": "0112345678",
    "address": "77 Galle Road, Colombo 03",
    "city": "Colombo"
  },
  "recipient": {
    "name": "Nimal Perera",
    "phone": "+94 77 123 4567",
    "address": "15 Peradeniya Road, Kandy",
    "city": "Kandy"
  },
  "parcel": {
    "weightKg": 1.5,
    "category": "Electronics",
    "size": "MEDIUM",
    "description": "Wireless headphones"
  },
  "destinationType": "OTHER_DISTRICT",
  "isExpress": false,
  "notes": "Fragile electronic device"
}
```

| Field | Validation Rule |
|---|---|
| `externalOrderRef` | Required string, 1 to 100 characters. Trimmed. Also accepts `external_order_ref`. |
| `sender`, `recipient`, `parcel` | Required objects |
| `*.name` | Required, at most 100 characters |
| `*.phone` | Required. Sri Lankan format: `^(\+94\|0)\d{9}$` after stripping spaces and dashes. |
| `*.address` | Required, 8 to 255 characters (excluding leading and trailing spaces) |
| `*.city` | Optional, at most 100 characters |
| `parcel.weightKg` | Required, 0.1 to 20 kg. Stored and priced rounded to 2 decimals. |
| `parcel.category` | Required, at most 40 characters |
| `parcel.size` | Optional: `SMALL`, `MEDIUM`, `LARGE` |
| `parcel.description` | Optional, at most 255 characters |
| `destinationType` | Optional: `SAME_CITY`, `OTHER_DISTRICT` |
| `isExpress` | Optional boolean (default `false`) |
| `notes` | Optional, at most 500 characters |

---

## 3. Response Contract

### 3.1 New Order (`201 Created`)
Returns `201 Created` with header `Location: /api/v1/merchant/orders/{id}`.

```json
{
  "id": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
  "trackingRef": "ZT-7K3QX9M2PA",
  "externalOrderRef": "ORD-2026-11-9876",
  "merchantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "CREATED",
  "fee": 670.00,
  "currency": "LKR",
  "estimatedDays": 2,
  "createdAt": "2026-11-03T10:15:30Z",
  "sender": {
    "name": "Apex Electronics",
    "phone": "0112345678",
    "address": "77 Galle Road, Colombo 03",
    "city": "Colombo"
  },
  "recipient": {
    "name": "Nimal Perera",
    "phone": "+94771234567",
    "address": "15 Peradeniya Road, Kandy",
    "city": "Kandy"
  },
  "parcel": {
    "weightKg": 1.50,
    "category": "Electronics",
    "size": "MEDIUM",
    "description": "Wireless headphones"
  }
}
```

### 3.2 Idempotent Replay (`200 OK`)
When the same merchant submits the same `externalOrderRef` with identical order details:
- Returns `200 OK` with the existing order payload.
- No `Location` header is returned.
- No duplicate parcel is created, and no additional pricing calculation occurs.

---

## 4. Idempotency & Duplicate Handling

- **Scope:** Uniqueness is strictly enforced per merchant: `UNIQUE (merchant_id, external_order_ref)`. Different merchants may use the same `externalOrderRef` without collision.
- **Request Hash:** A canonical SHA-256 hash of the normalized request is stored.
- **Identical Repeat:** Returns `200 OK` with original order.
- **Conflict:** If a merchant submits an existing `externalOrderRef` with different details, the API returns `409 Conflict`.
- **Concurrency:** Concurrent requests with the same reference are arbitrated by the database unique constraint; the second insert fails safely and replays the winner.

---

## 5. Security & Merchant Approval Enforcement

1. **Authentication:** Bearer token from Keycloak is required (`401 Unauthorized` if missing/invalid).
2. **Role Authorization:** Caller must have `ROLE_MERCHANT` (`403 Forbidden` for other roles).
3. **Approval Status:** `IdentityMerchantClient` checks with S1 (`GET /api/v1/users/me`):
   - Merchant application must be `APPROVED`.
   - User account status must be `ACTIVE`.
   - If pending, rejected, or inactive, returns `403 Forbidden`.
4. **Scoping:** The merchant subject is extracted directly from the verified token claim. Cross-merchant access is prevented.

---

## 6. Errors (RFC 7807)

All errors return `application/problem+json`:

| Status | When |
|---|---|
| 400 | Malformed JSON or invalid enum value |
| 401 | Missing, malformed, or expired access token |
| 403 | Missing `MERCHANT` role, unapproved merchant application, or inactive user account |
| 404 | Order ID does not exist or belongs to another merchant |
| 409 | `externalOrderRef` already exists for this merchant with different details |
| 422 | Field validation failed (e.g. invalid phone, weight out of bounds). Includes `fieldErrors` map. Personal data is never echoed in error messages. |
| 503 | Pricing service unavailable, identity service unreachable, or database storage failure. Nothing is saved; safe to retry. |
