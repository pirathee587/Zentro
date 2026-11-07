# REST API contracts

The checked-in OpenAPI 3.1 JSON files are the REST contract for paths as clients call them through the API Gateway. They are static documentation artifacts, not HTTP endpoints served by Spring.

| Service | Gateway path groups | OpenAPI contract |
|---|---|---|
| S1 Identity & Partner | `/api/v1/users/**`, `/api/v1/merchants/**`, `/api/v1/admin/{users,merchants,couriers}/**`, `/api/v1/courier/profile/**` | [`identity.openapi.json`](openapi/identity.openapi.json) |
| S2 Parcel & Pricing | `/api/v1/parcels/**`, `/api/v1/zones/**`, `/api/v1/merchant/orders/**`, `/api/v1/admin/pricing-rules/**` | [`parcel.openapi.json`](openapi/parcel.openapi.json) |
| S3 Dispatch & Courier | `/api/v1/dispatch/**`, `/api/v1/courier/deliveries/**` | [`dispatch.openapi.json`](openapi/dispatch.openapi.json) |
| S4 Tracking & Reporting | `/api/v1/tracking/**`; the gateway also reserves `/api/v1/reports/**`, with no active report controller | [`tracking.openapi.json`](openapi/tracking.openapi.json) |
| S5 Notification & Integration | `/api/v1/merchant/webhooks/**`, `/api/v1/admin/notification-attempts/**`, `/api/v1/admin/webhook-deliveries/**` | [`notifications.openapi.json`](openapi/notifications.openapi.json) |

The generator inventory and guard are maintained in [`build_openapi.py`](build_openapi.py). To regenerate the committed artifacts and verify that their operations match the real controller mappings:

```sh
python3 docs/api/build_openapi.py --generate
python3 docs/api/build_openapi.py --check
```

There is no springdoc dependency or generated `/v3/api-docs` or Swagger UI in the current build. The files above are reviewed and checked in directly. `--check` uses only Python's standard library and checks JSON/OpenAPI structure, required operation metadata and responses, references, path parameters, and equality with mapped `@RestController` endpoints.

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

Validation status is endpoint-specific: parcel booking and merchant-order validation use 422; fee estimate and most parameter/request-shape failures use 400. This contract preserves those differences. Gateway failures can be 502, 503, or 504; rate limiting can return 429. A known gateway sanitizer issue omits `instance` on sanitized upstream 5xx errors.

The gateway generates or forwards `X-Correlation-Id` and returns it on the response. Tracking paths are rate-limited to the configured 30 requests/minute default; other paths use the configured 300 requests/minute default. CORS origins are controlled by `CORS_ALLOWED_ORIGINS`; the checked-in default is localhost ports 3000 and 5173. These are implementation defaults, not availability guarantees.

`Idempotency-Key` on customer booking is optional and 1–100 nonblank characters when supplied. A newly created booking is 201 with `Location`; a matching replay is 200 without `Location`; same key with different input is 409.

## Inventory and source reconciliation

The generator currently inventories **51 HTTP operations/aliases**: S1 21, S2 10, S3 9, S4 4, and S5 7. It excludes controllerless gateway prefixes such as `/api/v1/admin/zones/**`, `/api/v1/admin/audit-logs/**`, and `/api/v1/reports/**`, and excludes nonexistent `GET /api/v1/parcels/{id}` despite the booking `Location` value referring to that future resource.

Existing endpoint guides remain useful for workflows and detailed operational behavior. The OpenAPI files are the path, role, parameter, body, and response contract. `notifications.md` previously said S5 added no HTTP endpoints despite documenting outcome-history routes later in the same file; that contradiction is corrected here and linked from the guide.

## Findings

| ID | Severity | Finding | Status |
|---|---|---|---|
| API-1 | High | S1 merchant API-key issuance returns the plaintext key once but does not set `Cache-Control: no-store`. The OpenAPI response does not claim the missing header; add it only after an explicitly reviewed runtime change. | Follow-up / needs owner decision |
| API-2 | Medium | S3 and S4 service security rules permit anonymous `/v3/api-docs/**`, but no springdoc dependency or docs handler is present, so no spec is currently served there. A future springdoc addition could expose internal operations directly on service ports. | Needs decision before enabling/publishing runtime docs |
| API-3 | Medium | Gateway sanitization of an upstream 5xx response omits ProblemDetail `instance`; a gateway test is skipped for this known shape mismatch. | Follow-up issue |
| API-4 | Low | `docs/api/notifications.md` said S5 had no HTTP endpoints while listing its HTTP routes in the same document. | Fixed in this change |

Reviewers should verify issue #146 against the source and existing endpoint tests. The issue should remain Todo / UNVERIFIED until @14Jannan approves.
