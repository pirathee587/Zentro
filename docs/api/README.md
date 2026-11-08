# REST API contracts

The checked-in OpenAPI 3.1 JSON files are the REST contract for paths as clients call them through the API Gateway. They are static documentation artifacts, not HTTP endpoints served by Spring.

| Service | Gateway path groups | OpenAPI contract |
|---|---|---|
| S1 Identity & Partner | `/api/v1/users/**`, `/api/v1/merchants/**`, `/api/v1/admin/{users,merchants,couriers}/**`, `/api/v1/courier/profile/**` | [`identity.openapi.json`](openapi/identity.openapi.json) |
| S2 Parcel & Pricing | `/api/v1/parcels/**`, `/api/v1/zones/**`, `/api/v1/merchant/orders/**`, `/api/v1/admin/pricing-rules/**` | [`parcel.openapi.json`](openapi/parcel.openapi.json) |
| S3 Dispatch & Courier | `/api/v1/dispatch/**`, `/api/v1/courier/deliveries/**` | [`dispatch.openapi.json`](openapi/dispatch.openapi.json) |
| S4 Tracking & Reporting | `/api/v1/tracking/**`; the gateway also reserves `/api/v1/reports/**`, with no active report controller | [`tracking.openapi.json`](openapi/tracking.openapi.json) |
| S5 Notification & Integration | `/api/v1/merchant/webhooks/**`, `/api/v1/admin/notification-attempts/**`, `/api/v1/admin/webhook-deliveries/**` | [`notifications.openapi.json`](openapi/notifications.openapi.json) |

S3's [workload metric definitions](dispatcher-workload.md) explain lifetime assignment counts, courier totals, timezone/reassignment limitations and #134 verification evidence.

The generator inventory and guard are maintained in [`build_openapi.py`](build_openapi.py). To regenerate the committed artifacts and verify that their operations match the real controller mappings:

```sh
python3 docs/api/build_openapi.py --generate
python3 docs/api/build_openapi.py --check
mvn --batch-mode -pl parcel-pricing-service -Dtest=OpenApiArtifactValidationTest test
```

There is no springdoc dependency or generated `/v3/api-docs` or Swagger UI in the current build. The files above are reviewed and checked in directly. `--check` uses only Python's standard library and checks JSON/OpenAPI structure, required operation metadata and responses, references, path parameters, and equality with mapped `@RestController` endpoints. The S2 test scope includes Swagger Parser and validates all five artifacts during Maven tests.

## Authentication and authorization

Protected operations use `bearerAuth` (Keycloak JWT). Operation descriptions state the service-level role rule; the gateway applies its own role rule before forwarding.

| Route group | Allowed role(s) |
|---|---|
| S1 `/users/**` profile operations and `/merchants/me` | CUSTOMER, MERCHANT, COURIER, DISPATCHER; self/owner scoping applies. Merchant application submission is CUSTOMER only. |
| S1 `/merchants/api-key/**` | MERCHANT; API key issuance additionally requires approved application and active account. |
| S1 `/admin/**` | DISPATCHER only. |
| S1 courier self-profile aliases | COURIER only. |
| S2 parcel booking; fee estimate | CUSTOMER only for booking; CUSTOMER or DISPATCHER for estimates. |
| S2 status update | DISPATCHER through the gateway. Direct internal S2 use also accepts SERVICE for the established S3 completion flow; SERVICE-only traffic is not admitted by the gateway. |
| S2 zones; admin pricing | CUSTOMER, MERCHANT, COURIER, DISPATCHER for zones; DISPATCHER only for pricing administration. |
| S2 merchant orders | MERCHANT with approved and active account; order resources are subject-scoped. |
| S3 dispatch queue, courier eligibility, workload and assignment | DISPATCHER only. |
| S3 courier deliveries and proof | COURIER only, with assignment ownership checked against courier and user identifiers. |
| S4 `/tracking/mine` | CUSTOMER or MERCHANT; result is scoped to the JWT subject. |
| S4 public tracking routes | Empty OpenAPI security: anonymous and human requests are accepted. SERVICE-only JWTs are explicitly rejected. |
| S5 merchant webhooks and delivery history | MERCHANT; resources are scoped to JWT subject. Registration and secret rotation require approved/active merchant. |
| S5 admin outcome history | DISPATCHER only. |

There is no ADMIN role. Operational administration is DISPATCHER-only. The gateway's `/actuator/health` is intentionally public. Service health endpoints are configured under Actuator; they are operational endpoints, not business API operations.

## Errors and shared headers

Errors use `application/problem+json` with the Spring ProblemDetail fields `type`, `title`, `status`, `detail`, and `instance` where the producing handler supplies them. Some business handlers add `code`, `fieldErrors`, `invalidFields`, or transition fields. `fieldErrors` keys are request field names.

Validation status is endpoint-specific: parcel booking and merchant-order validation use 422; fee estimate and most parameter/request-shape failures use 400. This contract preserves those differences. Gateway failures can be 502, 503, or 504; rate limiting can return 429. Gateway sanitized upstream 5xx problems now include `instance` (#144).

Release note (#144): unlisted `/api/v1/**` paths deliberately return 401 for anonymous requests or 403 for authenticated requests instead of 404, with safe problem+json and a correlation ID. Listed public and role routes retain their policy; unserved non-API paths retain 404. Customer-portal real mode supports estimate and booking only; dormant `/api/v1/customer/*` real calls have no gateway route. This gateway fallback is not an operation in the service OpenAPI files; resource-level 404 responses in those files remain valid.

The gateway generates or forwards `X-Correlation-Id` and returns it on the response. Tracking paths are rate-limited to the configured 30 requests/minute default; other paths use the configured 300 requests/minute default. CORS origins are controlled by `CORS_ALLOWED_ORIGINS`; the checked-in default is localhost ports 3000 and 5173. These are implementation defaults, not availability guarantees.

`Idempotency-Key` on customer booking is optional and 1–100 nonblank characters when supplied. A newly created booking is 201 with `Location`; a matching replay is 200 without `Location`; same key with different input is 409.

## Inventory and source reconciliation

The generator currently inventories **51 HTTP operations/aliases**: S1 21, S2 10, S3 9, S4 4, and S5 7. It excludes controllerless gateway prefixes such as `/api/v1/admin/zones/**`, `/api/v1/admin/audit-logs/**`, and `/api/v1/reports/**`, and excludes nonexistent `GET /api/v1/parcels/{id}` despite the booking `Location` value referring to that future resource. Inventory status: 51 documented and correct; 0 mismatches; 0 undocumented.

Existing endpoint guides remain useful for workflows and detailed operational behavior. The OpenAPI files are the path, role, parameter, body, and response contract. `notifications.md` previously said S5 added no HTTP endpoints despite documenting outcome-history routes later in the same file; that contradiction is corrected here and linked from the guide.

## Findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| API-2 | Medium | All five services currently permit anonymous `/v3/api-docs/**` in either the shared S1/S2/S5 security starter or S3/S4 security configuration, but no springdoc dependency or handler exists. Exposure tests check each service: `/v3/api-docs` returns 404 in S1/S2/S3/S5 and a generic 500 Problem Details response in S4; `/swagger-ui/index.html` returns 401 in all five. No spec content is served. Decision recorded: keep runtime docs absent; revisit protection if a handler is proposed later. | Decision recorded; no runtime change |
| API-3 | Medium | Gateway upstream 5xx sanitizer now uses ProblemWriter and includes `instance`; the strict shape test is re-enabled. | Fixed in #144; actual results in gateway audit |
| API-4 | Low | `docs/api/notifications.md` said S5 had no HTTP endpoints while listing its HTTP routes in the same document. | Fixed |

S1's existing `RoleBasedAuthorizationMatrixTests.merchantCanIssueOrRotateApiKey` asserts both `Cache-Control: no-store` and `Pragma: no-cache`; the corresponding operation documents both headers.

Reviewers should verify issue #146 against the source and existing endpoint tests. The issue should remain Todo / UNVERIFIED until @14Jannan approves.
