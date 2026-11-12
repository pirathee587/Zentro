# Merchant Approval to Keycloak Role Provisioning Runbook (G08 / Issue #190)

## 1. Executive Summary & Architecture Gap (Finding K4)

In the Zentro platform:
- **S1 (`identity-partner-service`)** is the authoritative source of truth for merchant application review, commercial details, and approval lifecycle (`PENDING` → `APPROVED` / `REJECTED` / `SUSPENDED` / `REINSTATED`).
- **Keycloak** is the identity provider (IdP) that issues signed OpenID Connect (OIDC) JSON Web Tokens (JWT) containing `realm_access.roles`.
- When an authorized `DISPATCHER` approves a merchant application via `PATCH /api/v1/admin/merchants/{applicationId}`, S1 transitions the status in `identity.merchant_application` to `APPROVED`.
- However, as proven in `MerchantApprovalGapIT` (Finding K4), S1 does not automatically invoke Keycloak Admin REST APIs or manipulate IdP user role mappings directly.
- Consequently, until the Keycloak `MERCHANT` role is assigned to the user's Keycloak account and a fresh token is minted, merchant API operations on S2 (`/api/v1/merchant/orders`) and S5 (`/api/v1/merchant/webhooks`) return `403 Forbidden`.

---

## 2. Policy Blocker & Design Decision Matrix

As specified under **Issue #190**, an automated cross-boundary provisioning mechanism requires formal architectural and security owner approval between two distinct strategies:

| Option | Architecture Pattern | Pros | Cons / Dependencies |
| :--- | :--- | :--- | :--- |
| **Option A (Current Standard)** | **Manual / Scripted Admin Provisioning Runbook** | Zero cross-service credential leakage; S1 has no high-privilege Keycloak admin credentials; clean separation of identity store. | Requires operator or dispatcher runbook execution upon application approval. |
| **Option B (Proposed Future)** | **Asynchronous Outbox Event Provisioning Worker** | Fully automated user experience; approval publishes `merchant.approved` outbox event; dedicated IAM worker provisions role. | Requires Keycloak Admin service account credentials, transport resilience, reconciliation job, and revocation handling. |

> **Current Decision Status**: Awaiting formal decision from `@pavi3alnx` and `@pirathee587`. In accordance with task constraints, S1 maintains strict boundary integrity without inventing unapproved direct IAM credentials.

---

## 3. Operational Runbook: Granting the `MERCHANT` Role

### Step 1: Dispatcher Approves Application in S1
The dispatcher reviews and approves the pending application via the Dispatcher Portal (`/dispatcher/merchant-approvals`) or REST API:
```http
PATCH /api/v1/admin/merchants/{applicationId}
Authorization: Bearer <dispatcher_token>
Content-Type: application/json

{
  "decision": "APPROVED"
}
```
S1 records the approval, reviewer subject, and timestamp in `identity.merchant_application`.

### Step 2: Administrator Runs Keycloak Provisioning Script
Using the administrative credentials configured in `infra/keycloak/.env`:

#### On Windows (PowerShell):
```powershell
./infra/keycloak/grant-merchant-role.ps1 -Email "merchant@example.com"
```
Or by Keycloak user subject UUID:
```powershell
./infra/keycloak/grant-merchant-role.ps1 -Subject "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
```

#### On Linux / macOS (Bash):
```bash
./infra/keycloak/grant-merchant-role.sh "merchant@example.com"
```

### Step 3: Token Refresh Behavior
- Existing access tokens held by the user retain their minted claims until expiration (`exp`).
- After the `MERCHANT` role is assigned in Keycloak:
  1. The user's frontend client triggers an OIDC silent renewal (`oidcManager.signinSilent()`), or
  2. The user signs out and signs back in.
- The newly minted JWT will contain:
  ```json
  {
    "realm_access": {
      "roles": ["CUSTOMER", "MERCHANT"]
    }
  }
  ```
- Downstream services (S2, S5) validate both:
  1. The caller has `ROLE_MERCHANT` in their JWT.
  2. S1 gRPC `GetMerchantStatus(merchantId)` returns `APPROVED` and user account status is `ACTIVE`.

---

## 4. Rejection and Suspension Behavior

- **Rejection**: When an application is `REJECTED`, S1 sets `status = 'REJECTED'` and requires `rejectionReason`. The user remains a `CUSTOMER` only. Even if an attacker manually injects a `MERCHANT` role in Keycloak, S1 gRPC returns `REJECTED`, blocking order creation.
- **Suspension**: When an approved merchant is `SUSPENDED` by a dispatcher, S1 sets `status = 'SUSPENDED'` with `suspension_reason`. S1 gRPC immediately returns `SUSPENDED`, failing closed and halting all merchant activity across S2 and S5 regardless of unexpired token claims.
- **Revocation Script**: To remove the `MERCHANT` role in Keycloak:
  ```powershell
  ./infra/keycloak/grant-merchant-role.ps1 -Email "merchant@example.com" -Revoke
  ```
