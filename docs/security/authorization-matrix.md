# ZD-094 Cross-Role Authorization Negative Test Matrix (#169)

**Status: Verified.** Tests added in this branch exercise the authorization boundaries
audited below. Each test is a negative (or complementary positive baseline) check that
runs the real Spring Security `@PreAuthorize` / `SecurityFilterChain` layer without a
live Keycloak or database, using the `jwt()` MockMvc post-processor from
`spring-security-test`.

## Scope

Five backend services — S1 through S5. Frontend role guards are out of scope. Only
genuinely missing negative test cases were added; existing tests were not duplicated.

## Audit Summary

### S1 — Identity & Partner Service

| Test class | Coverage |
|---|---|
| `RoleBasedAuthorizationMatrixTests` | Full role matrix for all identity/partner endpoints |
| `IdentityAuthorizationTests` | Unauthenticated → 401, wrong role → 403 per endpoint |
| `UserProfileOwnershipTest` | JWT `sub` ownership on `/api/v1/users/me` |
| `AdminUserControllerTest`, `AdminUserApiHardeningTest` | DISPATCHER-only `/api/v1/admin/users/**` |
| `AdminCourierControllerTest` | DISPATCHER-only `/api/v1/admin/couriers/**` |
| `MerchantApiKeyControllerTest` | MERCHANT-only `/api/v1/merchants/api-key` |
| `CourierSelfProfileControllerTest` | COURIER-only `/api/v1/courier/profile` |
| `ZeroTrustSecurityTest` | Zero-trust JWT validation (bad sig / exp / iss / aud) |

**Verdict:** Fully covered. No new tests needed.

### S2 — Parcel & Pricing Service

| Test class | Coverage |
|---|---|
| `BookingSecurityIntegrationTest` | MERCHANT/COURIER/DISPATCHER/SERVICE → 403 on `POST /api/v1/parcels` |
| `ParcelStatusControllerTest` | CUSTOMER/MERCHANT/COURIER → 403 on `PATCH /api/v1/parcels/{id}/status` |
| `MerchantOrderControllerTest` | CUSTOMER/COURIER/DISPATCHER/SERVICE/ADMIN → 403 on `/api/v1/merchant/orders/**`; merchant-to-merchant IDOR → 404 |
| `ParcelPricingAuthorizationAndEndpointTests` | Full role matrix for pricing/zone endpoints |
| `ZeroTrustJwtSecurityTest` | Zero-trust JWT validation |

**Verdict:** Fully covered. No new tests needed.

### S3 — Dispatch & Courier Service

| Test class | Coverage |
|---|---|
| `DispatchQueueControllerTest` | CUSTOMER/MERCHANT/COURIER → 403 on all `/api/v1/dispatch/**` endpoints; anon → 401 |
| `CourierDeliveryControllerTest` | CUSTOMER/MERCHANT/DISPATCHER/SERVICE → 403 on all `/api/v1/courier/deliveries/**` endpoints; wrong-courier IDOR → 403; anon → 401 |
| `ZeroTrustJwtSecurityTest` | Zero-trust JWT validation with real RSA key / JWKS server |
| `DispatchPermissionsTest` | `DispatchPermissions` helper tests |

**Verdict:** Fully covered. No new tests needed.

### S4 — Tracking & Reporting Service

| Test class | Coverage |
|---|---|
| `ZeroTrustJwtSecurityTest` | COURIER/DISPATCHER denied on `GET /api/v1/tracking/mine` → 403; anon → 401; SERVICE-only rejected; public tracking anon permitted |
| `CustomerTrackingControllerTest` | Customer tracking ownership |
| `PublicTrackingControllerTest` | Public tracking access |

**Verdict:** Fully covered. No new tests needed.

### S5 — Notification & Integration Service

| Test class | Coverage |
|---|---|
| `MerchantWebhookApiTest` | CUSTOMER/COURIER/DISPATCHER/SERVICE → 403 on all `/api/v1/merchant/webhooks/**` endpoints; merchant-to-merchant IDOR → 404; anon → 401 |
| `WebhookSecurityTest`, `WebhookReplayProtectionTest`, `WebhookSignatureVerifierTest` | Webhook signature and replay protection |
| `ZeroTrustJwtSecurityTest` | Zero-trust JWT validation |
| **`IntegrationAttemptControllerTest`** ← **new** | CUSTOMER/MERCHANT/COURIER/SERVICE → 403 on `GET /api/v1/admin/notification-attempts` and `GET /api/v1/admin/webhook-deliveries`; anon → 401; DISPATCHER → 200 (baseline) |

**Gap fixed:** `IntegrationAttemptController` had no existing wrong-role test coverage. The
new `IntegrationAttemptControllerTest` adds the missing negative cases.

---

## Role/Endpoint Cross-Reference (Negative Matrix)

Legend: ✅ = Allowed, ❌ = Denied (403/401), 🧪 = test exists, **bold** = added in this branch.

| Endpoint | CUSTOMER | MERCHANT | COURIER | DISPATCHER | SERVICE | anon | Test |
|---|---|---|---|---|---|---|---|
| `GET /api/v1/tracking/mine` | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ | S4 `ZeroTrustJwtSecurityTest` |
| `POST /api/v1/parcels` | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ | S2 `BookingSecurityIntegrationTest` |
| `PATCH /api/v1/parcels/{id}/status` | ❌ | ❌ | ❌ | ✅ | ✅* | ❌ | S2 `ParcelStatusControllerTest` |
| `GET /api/v1/merchant/orders/**` | ❌ | ✅† | ❌ | ❌ | ❌ | ❌ | S2 `MerchantOrderControllerTest` |
| `GET /api/v1/dispatch/**` | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | S3 `DispatchQueueControllerTest` |
| `POST /api/v1/dispatch/assignments` | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | S3 `DispatchQueueControllerTest` |
| `GET /api/v1/courier/deliveries/**` | ❌ | ❌ | ✅‡ | ❌ | ❌ | ❌ | S3 `CourierDeliveryControllerTest` |
| `POST /api/v1/courier/deliveries/{id}/proof` | ❌ | ❌ | ✅‡ | ❌ | ❌ | ❌ | S3 `CourierDeliveryControllerTest` |
| `GET /api/v1/merchant/webhooks/**` | ❌ | ✅† | ❌ | ❌ | ❌ | ❌ | S5 `MerchantWebhookApiTest` |
| `GET /api/v1/admin/notification-attempts` | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | **S5 `IntegrationAttemptControllerTest`** |
| `GET /api/v1/admin/webhook-deliveries` | ❌ | ❌ | ❌ | ✅ | ❌ | ❌ | **S5 `IntegrationAttemptControllerTest`** |

\* `SERVICE` allowed on `PATCH /api/v1/parcels/{id}/status` for internal service-to-service calls (S3 → S2 on internal port, bypassing gateway).  
† MERCHANT scoped to own subject via JWT `sub` (IDOR-safe by design).  
‡ COURIER scoped to own assigned deliveries (wrong-courier IDOR → 403).

---

## Test Locations

| New test file | Service |
|---|---|
| `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/admin/controller/IntegrationAttemptControllerTest.java` | S5 |

---

## Reproduction

```bash
cd backend/notification-integration-service
mvn --batch-mode --no-transfer-progress test
```

Or from the backend root:

```bash
cd backend
mvn --batch-mode --no-transfer-progress test package
```

