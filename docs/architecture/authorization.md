# Zentro Authorization and Access Control Architecture (ZD-10)

## 1. Overview

Zentro implements role-based access control (RBAC) and strict resource-level ownership enforcement across the API Gateway and backend microservices.

Authentication is provided via Keycloak (OAuth2 / OIDC). Downstream services and the API Gateway validate JWT tokens issued by Keycloak and translate `realm_access.roles` into Spring Security granted authorities.

### Core Roles
In accordance with the Zentro project architecture:
- **`CUSTOMER`**: End users who send parcels, view their personal deliveries, and can apply to become merchants.
- **`MERCHANT`**: Business partners who manage store orders, integrate via merchant API keys, and configure webhooks.
- **`COURIER`**: Delivery personnel who execute assigned parcel pickups and drop-offs.
- **`DISPATCHER`**: Operational and administrative controllers who oversee dispatching, parcel lifecycle, courier workloads, pricing rules, and administrative duties (such as reviewing merchant applications and user administration). In this architecture, administrative and dispatch operations are consolidated under the `DISPATCHER` authority.
- **`SERVICE`**: Technical inter-service identity (not permitted to access user-facing human routes).

---

## 2. Role-to-Action Permission Matrix

| Action / Capability | Customer | Merchant | Courier | Dispatcher / Admin |
|---|---|---|---|---|
| Public tracking lookup (`GET /api/v1/tracking/{number}`) | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes |
| Real-time tracking stream (`GET /api/v1/tracking/{number}/stream`) | ✅ Own only | ✅ Own only | ✅ Assigned only | ✅ Yes |
| View personal deliveries (`GET /api/v1/tracking/mine`) | ✅ Own only | ✅ Own only | ❌ Denied | ❌ Denied |
| Submit merchant application (`POST /api/v1/merchants`) | ✅ Yes | ❌ Denied | ❌ Denied | ❌ Denied |
| View own merchant application (`GET /api/v1/merchants/me`) | ✅ Own only | ✅ Own only | ✅ Own only | ✅ Own only |
| Review / approve merchant applications (`PATCH /api/v1/admin/merchants/{id}`) | ❌ Denied | ❌ Denied | ❌ Denied | ✅ Yes |
| List pending merchant applications (`GET /api/v1/admin/merchants`) | ❌ Denied | ❌ Denied | ❌ Denied | ✅ Yes |
| Access merchant API keys (`/api/v1/merchants/api-key`) | ❌ Denied | ✅ Approved only | ❌ Denied | ❌ Denied |
| Read/Update own profile (`GET/PATCH /api/v1/users/me`) | ✅ Own only | ✅ Own only | ✅ Own only | ✅ Own only |
| Create personal parcels (`POST /api/v1/parcels`) | ✅ Yes | ❌ Denied | ❌ Denied | ❌ Denied |
| Estimate parcel price (`POST /api/v1/parcels/estimate`) | ✅ Yes | ❌ Denied | ❌ Denied | ✅ Yes |
| View/Update assigned deliveries (`/api/v1/courier/**`) | ❌ Denied | ❌ Denied | ✅ Assigned only | ❌ Denied |
| Assign deliveries & manage dispatch (`/api/v1/dispatch/**`) | ❌ Denied | ❌ Denied | ❌ Denied | ✅ Yes |
| View operational / audit reports (`/api/v1/reports/**`) | ❌ Denied | ❌ Denied | ❌ Denied | ✅ Yes |
| Manage delivery zones and pricing (`/api/v1/admin/zones/**`) | ❌ Denied | ❌ Denied | ❌ Denied | ✅ Yes |

---

## 3. Role-Based Authorization vs. Resource Ownership

### Role-Based Endpoint Protection (Course-Grained)
Endpoints are guarded by Spring Security `@PreAuthorize` method annotations and Gateway route-matching predicates:
- `@PreAuthorize("hasRole('DISPATCHER')")`: Limits administrative and dispatch operations to dispatchers.
- `@PreAuthorize("hasRole('MERCHANT')")`: Limits API key management to merchants.
- `@PreAuthorize("hasRole('CUSTOMER')")`: Limits new merchant onboarding to customer accounts.
- `@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER')")`: Protects general human profile endpoints.

### Resource Ownership Enforcement (Fine-Grained)
Role checks alone are insufficient to guarantee security. Fine-grained ownership checks ensure that:
1. **No Cross-User Access:**
   - User profile endpoints (`/api/v1/users/me`) never accept arbitrary user IDs in query parameters or request payloads. The profile is resolved server-side directly from the verified token claim `jwt.getSubject()`.
2. **No Cross-Merchant Access:**
   - Merchant API keys (`/api/v1/merchants/api-key`) are strictly linked to `app_user.id` resolved from `jwt.getSubject()`. A merchant cannot read, rotate, or revoke keys belonging to another merchant.
   - Merchant applications are keyed by `app_user_id`. Calling `/api/v1/merchants/me` returns only the caller's application.
3. **Approval Lifecycle Enforcement (ZD-050):**
   - A merchant's application can be in one of four states: `PENDING`, `APPROVED`, `REJECTED`, or `SUSPENDED`.
   - Only `APPROVED` merchants may issue/rotate API keys, create orders, or manage webhooks. Any other state returns `403 Forbidden`.
   - Dispatchers can review pending applications (`PATCH /api/v1/admin/merchants/{id}`), suspend approved merchants (`PATCH /api/v1/admin/merchants/{id}/suspend`), and reinstate suspended merchants (`PATCH /api/v1/admin/merchants/{id}/reinstate`).
   - Permitted state transitions: `PENDING → APPROVED`, `PENDING → REJECTED`, `PENDING → SUSPENDED`, `APPROVED → SUSPENDED`, `SUSPENDED → APPROVED`, `REJECTED → PENDING` (merchant re-applies).
   - Suspension and rejection require a non-blank reason for auditability.
   - The gRPC `getMerchantStatus` RPC queries the actual `merchant_application` table (previously returned a hardcoded `ACTIVE` stub).
4. **Courier Assignment Isolation:**
   - Courier endpoints require that status updates and delivery job views only access parcels assigned to that courier's identifier.

---

## 4. HTTP Error Handling & Status Codes (RFC 7807)

Security errors return standardized RFC 7807 `application/problem+json` payloads:
- **`401 Unauthorized`**:
  - Returned when authentication credentials (Bearer JWT) are missing, expired, malformed, or have an invalid signature/audience.
  - Handled by `ProblemDetailsSecurityHandlers.authenticationEntryPoint(...)` and Gateway `ProblemWriter`.
  - Detail message: `"Authentication required"` / `"Authentication is required or the bearer token is invalid."`
- **`403 Forbidden`**:
  - Returned when an authenticated user does not possess the required role authority (e.g. CUSTOMER trying to access `/api/v1/admin/merchants`), or fails a resource ownership / status rule (e.g. unapproved merchant trying to generate an API key).
  - Handled by `ProblemDetailsSecurityHandlers.accessDeniedHandler(...)` and Gateway `ProblemWriter`.
  - Detail message: `"Access denied"` / `"You do not have access to this resource."`
- **`404 Not Found`**:
  - Returned when a requested resource does not exist, or non-API routes are targeted.
  - Unlisted `/api/v1/**` gateway paths deliberately changed from 404 to 401 (anonymous) or 403 (authenticated) in #144. Listed endpoint resource 404 responses are unchanged. The fail-closed fallback also denies a newly added route without an explicit security rule.
- **`409 Conflict`**:
  - Returned when an operation violates state constraints (e.g., submitting a merchant application when one is already pending/approved, reviewing an application that is not in `PENDING` state, or attempting to suspend a merchant who is not in `APPROVED` state).
- **`400 Bad Request`**:
  - Returned when request validation fails (e.g. rejection without reason, invalid phone format).

---

## 5. Gateway vs. Downstream Service Responsibilities

### API Gateway (`api-gateway`)
- Edge security boundary: validates Keycloak JWT issuer, signature, expiry, and audience (`zentro-api`).
- Rejects requests with invalid or expired tokens with `401 Unauthorized`.
- Applies perimeter route authorization (e.g. rejecting non-dispatchers from `/api/v1/admin/**`).
- Injects correlation IDs (`X-Correlation-Id`) and enforces IP rate limits (e.g. 30 req/min on tracking).
- Drops untrusted or forged identity headers from external clients.

### Downstream Services (e.g. `identity-partner-service`, `parcel-pricing-service`)
- Enforces independent security using `zentro-security-starter`.
- Does **not** assume upstream requests are trustworthy merely because they passed through the Gateway.
- Validates the JWT and authorities locally via `@EnableMethodSecurity`.
- Enforces database-backed resource ownership and business lifecycle constraints (e.g., active merchant status check in `MerchantApiKeyService`).

---

## 6. Automated Test Coverage

The authorization architecture is verified by automated test suites:
1. **`RoleBasedAuthorizationMatrixTests`** (`identity-partner-service`):
   - Unauthenticated access returns 401 across all endpoints.
   - Non-dispatchers receive 403 on admin merchant review, suspend, and reinstate endpoints.
   - Dispatchers receive 200 on admin merchant review, suspend, and reinstate endpoints.
   - Dispatcher can list all applications without filter, or filter by PENDING/APPROVED/REJECTED/SUSPENDED.
   - Non-merchants receive 403 on API key endpoints.
   - Non-customers receive 403 on merchant application submission.
   - All human roles can read/update their own profile.
2. **`MerchantApiKeyServiceTest`** (`identity-partner-service`):
   - Approved, active merchants can generate and rotate API keys.
   - Unapproved or pending merchants receive 403 Forbidden.
   - Revocation marks key inactive and sets timestamp.
   - Strict `app_user_id` query scoping prevents cross-merchant data leaks.
3. **`MerchantLifecycleServiceTest`** (`identity-partner-service`):
   - Review from PENDING to APPROVED, REJECTED, SUSPENDED — including reason validation.
   - Suspend approved merchant (APPROVED → SUSPENDED): requires reason, state enforcement.
   - Reinstate suspended merchant (SUSPENDED → APPROVED): state enforcement.
   - Invalid transitions return 409 Conflict; non-existent applications return 404 Not Found.
   - Status filter validation in `list()` (valid values / invalid / case-insensitive).
4. **`ProblemDetailsSecurityHandlersTest`** (`zentro-security-starter`):
   - Verifies RFC 7807 compliance for 401 and 403 errors.
5. **`GatewayBehaviorTests`** (`api-gateway`):
   - Verifies edge route protection, public tracking lookup, and bearer token validation.

---

## 6. Live Tracking Authorization & SSE Security (ZD-060)

### Public Tracking Security
- `GET /api/v1/tracking/{trackingRef}` and `GET /api/v1/tracking/{trackingRef}/events` (JSON) are privacy-preserving public inquiry endpoints.
- **Exposed fields:** `trackingRef`, `status`, `rawStatus`, `category`, `size`, `description`, `originCity`, `destinationCity`, `estimatedDays`, `estimatedDeliveryDate`, `createdAt`, `deliveredAt`, `failureReason`, and event history.
- **Strictly excluded:** Customer full names, phone numbers, email addresses, complete street addresses, financial fees, internal UUIDs, courier identity, and raw database entities.
- `SERVICE`-only tokens are rejected with `403 Forbidden` to enforce separation of concerns.

### Authenticated Live Tracking Streams (SSE)
- `GET /api/v1/tracking/{trackingRef}/stream` and `GET /api/v1/tracking/{trackingRef}/events` with `Accept: text/event-stream` require backend authentication and fine-grained resource authorization via `DeliveryAuthorizationService`.
- **Access Rules:**
  - **`CUSTOMER`**: Can stream only deliveries where `parcel.customerSubject == jwt.getSubject()`.
  - **`MERCHANT`**: Can stream only deliveries where `parcel.merchantSubject == jwt.getSubject()`.
  - **`COURIER`**: Can stream only deliveries assigned to them (`parcel.courierSubject == jwt.getSubject()`).
  - **`DISPATCHER`**: Platform-wide operational access.
  - **Unauthorized / Mismatched Owners:** Rejected with `403 Forbidden` (`AccessDeniedException`).
  - **Unauthenticated / Expired Tokens:** Rejected with `401 Unauthorized` (`ResponseStatusException`).
- **Connection Lifecycle Security:**
  - Token expiration is verified on connection establishment and revalidated upon each broadcast; expired emitters are immediately completed and evicted.
  - Resource ownership is revalidated upon each event emission against the current database state (e.g. if a courier is unassigned, subscription is revoked).
  - Periodic background cleanup task (`cleanupExpiredSubscribers`) purges dead/expired emitters.
  - Live event payloads contain strictly safe event attributes (`trackingRef`, `status`, `title`, `description`, `occurredAt`) with zero PII or internal identifiers.
