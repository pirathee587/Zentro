# Gateway route and timeout audit (#144)

Status: UNVERIFIED pending independent review and the remaining error-shape follow-ups below. Base: `dc82cb7e09a4c72aba05e0aba28edba3f5c1f3ac`. No service code, databases, JWT role model or service contracts changed. Gateway unknown-path status changes were explicitly approved.

## Findings (serious risks first)

| ID | Severity / classification | Evidence | Status |
|---|---|---|---|
| SEC-1 | Medium / PROVEN GAP, future exposure risk | `GatewaySecurityConfig.java:73` previously permitted unlisted `/api/v1/**`. No public internal controller exposure was found. | Fixed with approved denyAll: deliberate unknown-path 404 → 401 anonymous / 403 authenticated. Test-only unsecured route proves fail-closed even when forwarding exists. |
| ERR-1 | Medium / PROVEN GAP | Upstream sanitizer's fixed JSON lacked `instance`; frontend `shared/api/apiClient.ts:41` already accepts it optionally and its test covers it. | Fixed: use existing ProblemWriter; disabled API-3 shape test re-enabled. |
| ERR-2 | Low / PROVEN GAP | CorrelationIdFilter was a routing GlobalFilter, which never ran for security/early 404 failures. | Fixed: WebFilter before security and NonApiNotFoundFilter; negative HTTP tests assert correlation headers. |
| CORS-1 | Medium / PROVEN GAP | CorsConfig allows Authorization, Content-Type, X-Correlation-Id only. Browser Idempotency-Key preflight fails; Location is not exposed. | Follow-up text below; report-only per scope. |
| RES-1 | Medium / RISK | No configured total proxy body limit or server read/idle deadline; elastic pool is effectively unbounded. Existing integration test forwards 1 MiB. | Follow-up capacity/slow-client review; no resource-control feature added. |
| TIME-1 | Low / RISK | Entire tracking route, including reserved reports and ordinary GETs, inherits a one-hour response deadline intended for SSE. Response timeout is not a total stream lifetime deadline. | Follow-up route-class/load review; no route/timeout contract changed. |
| ERR-3 | Low / RISK | Existing sanitizer handles writeWith/writeAndFlushWith, not an explicit setComplete-only 5xx path; an unending error body is drained before replacement. | Follow-up test/design for empty and streaming failures; no status-policy change in this PR. |
| ERR-4 | Low / PROVEN GAP | Existing Spring Security firewall rejects encoded slash before authorization with empty 400 plus correlation ID, not problem+json. | Follow-up/needs decision: preserve firewall rejection; adding a safe 400 problem body needs a separate response-shape decision. Do not weaken firewall normalization. |
| OK-1 | OK | All 55 controller method/path combinations resolve to their correct effective S1–S5 owner. | Automated source/config comparison and stub forwarding tests. |
| OK-2 | OK | S1 courier profile precedes overlapping S3 courier prefix; no rewrite/prefix filter changes the URI. | Intentional ordered overlap pinned by tests. |
| OK-3 | OK | No Retry or CircuitBreaker gateway filter is configured. POST timeout produces exactly one upstream hit. | Existing design preserved; bounded-time/recovery test added. |
| OK-4 | OK | Identity headers stripped; JWT Authorization intentionally forwarded and revalidated downstream. Routing uses Path only. | Existing identity tests plus spoofed Host/X-Forwarded-Host/Prefix/Proto forwarding test. |

No actual public internal/management endpoint, authorization bypass, wrong service target or retry on booking was demonstrated. Health is intentionally public. Prometheus intentionally allows human roles; tightening that policy would require a separate decision.

## Real route table

Source: `backend/api-gateway/src/main/resources/application.yml:17`. No Java route definitions were found. Each row has one Path predicate with comma-separated alternatives (OR). There are no Method, Header or Host predicates. All route orders default to 0; declaration order resolves the intentional courier overlap. No route/default filters, StripPrefix, RewritePath, PrefixPath, Retry or CircuitBreaker are configured; paths, methods, queries and bodies are forwarded unchanged. Global filters sanitize identity/upstream errors, rate-limit and log requests.

| Route | Path alternatives | Target variable / host-run default | Effective gateway auth | Public/internal |
|---|---|---|---|---|
| identity | `/api/v1/users/**`, `/api/v1/merchants/**`, `/api/v1/admin/merchants/**`, `/api/v1/admin/users/**`, `/api/v1/admin/couriers/**`, `/api/v1/courier/profile/**` | `S1_URI` / `http://localhost:8081` (identity-partner-service) | users/merchants: human; admin: DISPATCHER; courier: COURIER | Protected business APIs |
| parcel | `/api/v1/zones/**`, `/api/v1/parcels/**`, `/api/v1/merchant/orders/**`, `/api/v1/admin/zones/**`, `/api/v1/admin/pricing-rules/**` | `S2_URI` / `http://localhost:8082` (parcel-pricing-service) | zones: human; booking POST: CUSTOMER; estimate POST aliases: CUSTOMER or DISPATCHER; status PATCH: DISPATCHER; remaining parcels: deny; merchant orders: MERCHANT; admin: DISPATCHER | Protected business APIs |
| dispatch | `/api/v1/dispatch/**`, `/api/v1/courier/**` | `S3_URI` / `http://localhost:8083` (dispatch-courier-service) | DISPATCHER / COURIER | Protected; courier profile resolves to preceding S1 route |
| tracking | `/api/v1/tracking/**`, `/api/v1/reports/**` | `S4_URI` / `http://localhost:8084` (tracking-reporting-service) | mine: CUSTOMER/MERCHANT; tracking GET reference/events/stream: anonymous or non-SERVICE-only; other tracking: deny; reports: DISPATCHER | Public tracking only; mine/reports protected |
| notification | `/api/v1/merchant/webhooks/**`, `/api/v1/admin/audit-logs/**`, `/api/v1/admin/notification-attempts/**`, `/api/v1/admin/webhook-deliveries/**` | `S5_URI` / `http://localhost:8085` (notification-integration-service) | MERCHANT / DISPATCHER | Protected business APIs |

Docker examples use service DNS names with the same ports (`backend/api-gateway/.env.example:9`). No upstream actuator, management, OpenAPI/Swagger, `/internal`, `/error`, gRPC or Kafka endpoint is explicitly proxied. `/actuator/health` and descendants are local/public; `/actuator/prometheus` is local/human-authenticated. Non-API unlisted paths return local problem 404 before routing. An `/internal` suffix under a broad business prefix is not categorically blocked; no active internal controller exists there. Service ports must remain private in deployment; that is not established by these gateway tests.

### Route versus endpoint comparison

| Owner | Active method/path combinations | Result | Exceptions |
|---|---:|---|---|
| S1 | 25 | Correct effective identity route | Two courier-profile paths also match dispatch, intentionally losing to identity |
| S2 | 10 | Correct parcel route | `/api/v1/admin/zones/**` reserved; no controller |
| S3 | 9 | Correct dispatch route | No endpoint gap |
| S4 | 4 | Correct tracking route | `/api/v1/reports/**` reserved; no controller |
| S5 | 7 | Correct notification route | `/api/v1/admin/audit-logs/**` reserved; no controller |

These three reserved prefixes are explicitly allow-listed in GatewayInventoryTests with reasons. No route is entirely without active endpoints. Route predicates intentionally accept more methods than controllers: unsupported methods reach services where gateway role policy allows them; service 405 responses pass through. Parcel methods are specifically restricted by gateway security. No method restriction was added.

Controller inventory is generated from current concrete REST-controller mapping annotations by GatewayInventoryTests (source audit, not service boot/reflection). It asserts the effective ordered owner and rejects unexplained prefixes. This parser supports the current direct annotations and literal paths; composed annotations, conditional controllers or future dynamic mappings require extending it. Framework actuator/docs/error endpoints are classified separately above.

## Timeouts and resource limits

| Route | Before → after connect | Before → after response | Configuration |
|---|---|---|---|
| identity | 1000 → 1000 ms | 30 → 30 seconds | Inherits `GATEWAY_CONNECT_TIMEOUT_MS`, `GATEWAY_RESPONSE_TIMEOUT` |
| parcel | 1000 → 1000 ms | 30 → 30 seconds | Same global settings |
| dispatch | 1000 → 1000 ms | 30 → 30 seconds | Same global settings |
| tracking | 1000 → 1000 ms | 3600000 → 3600000 ms | Global connect; metadata `GATEWAY_TRACKING_RESPONSE_TIMEOUT_MS` |
| notification | 1000 → 1000 ms | 30 → 30 seconds | Same global settings |

All routes already inherit explicit environment-configurable finite timeouts; no new per-route duplicate defaults were needed. Configuration binding tests assert defaults and overrides. Timeout HTTP tests override response to 150 ms and use a 750 ms delayed upstream. SSE requires a distinct operational budget; the one-hour setting is preserved.

S3 identity/service-token clients have 3-second connect and 5-second read timeouts; S5 merchant lookup uses 3/5 seconds and webhook delivery defaults to 2/5 seconds. These are outbound dependency deadlines, not total service operation deadlines. There is no proven slowest-operation budget or load evidence to justify new gateway values. Frontend shared HTTP client timeout is 5 seconds, shorter than gateway 30 seconds (see frontend client configuration); aligning end-to-end budgets needs owner agreement. Database/Kafka and sequential operations can extend service latency. No claim that every legitimate operation fits these budgets is made.

Dependency bytecode inspected locally with javap: Gateway 4.3.5 HttpClientProperties.Pool defaults ELASTIC, null idle/lifetime, eviction zero and FIFO. HttpClientFactory sets elastic maximum connections to Integer.MAX_VALUE, pending acquire timeout zero and pending count -1; configured maxConnections/acquireTimeout apply to FIXED mode. No pool overrides exist. Reactor Netty 1.2.18 HttpDecoderSpec defaults: initial line 4096 bytes, headers 8192 bytes, chunks 8192 bytes, header validation true. Chunk size is not a total-body limit. No RequestSize filter is installed. Codec aggregation limits do not bound streamed proxy bodies. No slow-upload or pool exhaustion load test was run; this is RES-1, not a feature added here.

## Error and header behavior

| Failure class | Gateway response | Shape / limits |
|---|---|---|
| Connection refused / DNS / no route to host | 503 | Safe problem+json |
| Response/connect/read timeout | 504 | Safe problem+json |
| Malformed upstream HTTP / other transport failure | 502 | Safe problem+json |
| Upstream 5xx body | 502 | Sanitized problem+json, now includes instance |
| Upstream 4xx, including 400/404/405/422/429 | Original status/body | Intentionally passed through; preserves fieldErrors/invalidFields. Service body safety remains service responsibility |
| Unlisted API path | 401 anonymous / 403 authenticated | Deliberate approved change from 404; safe problem and correlation ID |
| Non-API/internal path | 404 | Safe local problem |
| Authentication / authorization | 401 / 403 | Safe local problem |
| Gateway rate limit | 429 plus Retry-After | Safe local problem |
| HTTP parser rejection before WebFlux | Parser status or closed connection | Standard application shape/correlation cannot be guaranteed before request acceptance |
| Encoded-slash firewall rejection | Existing empty 400 | Correlation ID present; ERR-4 shape follow-up; no upstream request |

Local problem fields are type/title/status/detail/instance. Correlation is the `X-Correlation-Id` response header, not an added JSON field. `instance` contains the request path, never an upstream URL. Gateway-generated details omit hostnames, ports, service names, traces and upstream bodies. Existing upstream 4xx are not rewritten. A malformed JSON body with valid HTTP is forwarded: the gateway does not validate service payload contracts.

IdentityHeaderSanitizingFilter.java:16 removes X-User-Id, X-User, X-Roles, X-Role, X-User-Roles, X-Auth-User, X-Auth-Roles, X-Authenticated-User, X-Authenticated-Roles, X-Principal, X-Forwarded-User, X-Forwarded-Roles, X-Remote-User, X-Remote-Groups. Authorization is intentionally retained. Host does not participate in route predicates and PreserveHostHeader is absent. Forwarded headers are not trusted for role decisions; rate limiting defaults `TRUST_X_FORWARDED_FOR=false`. Setting it true without a trusted edge allows IP-based bucket spoofing. Tests show spoofed Host/forwarding values do not alter path/target/auth; they do not establish deployed proxy trust correctness or downstream URL-generation safety.

CORS defaults: origins http://localhost:3000 and http://localhost:5173, configurable via CORS_ALLOWED_ORIGINS; methods GET/POST/PUT/PATCH/DELETE/OPTIONS; allowed headers Authorization/Content-Type/X-Correlation-Id; exposed header X-Correlation-Id; credentials false. maxAge is unset (no explicit preflight cache lifetime). Existing tests cover permitted/rejected origins and observe the denied Idempotency-Key preflight. No CORS production change was authorized here.

## Follow-up text and owners

Owners verified with gateway git history and #26: @pirathee587 maintains gateway; #144 implementation owner remains @Deadsecnote1, independent reviewer @kirusigan03.

- **CORS-1 — owner @pirathee587:** approve browser Idempotency-Key request header and Location exposure, then add positive/negative preflight tests and update gateway docs. No matching merged CORS fix found during inspection.
- **RES-1/TIME-1 — owner @pirathee587:** define total request/slow-client limits, pool capacity and SSE versus ordinary/report operation budgets; coordinate infrastructure owner and measure under load before config changes.
- **ERR-3 — owner @pirathee587:** reproduce empty/completing and streaming 5xx variants, approve status compatibility where necessary, then test cancellation/bounded consumption and safe problem output.
- **ERR-4 — owner @pirathee587:** approve a safe problem body for firewall-rejected requests while preserving 400 and strict path rejection, then add equivalent tests. This audit records the current empty-body exception.

No service-side change was needed. S1–S5 code and service OpenAPI contracts remain unchanged. Gateway README (#26), testing guide (#66), permission matrix (#116), authorization guide and API README were updated for the approved status change and fixed API-3 finding. No service OpenAPI file claimed an unknown-path gateway 404; their operation/resource 404 responses remain correct. WARP.md contains no contradictory unknown-path statement and was unchanged. Historical test evidence is retained and labeled historical.

## Verification and evidence

Results are recorded after commands complete; pending entries are not passes. Not verified: deployed environment, Kubernetes ingress/network isolation, TLS termination at Nginx, production timeout/pool behavior under load, shared/live Keycloak, authenticated real-service HTTP flows. The integration fixture uses a real disposable Keycloak and distinct stub upstreams, not live services.

| Evidence | Result |
|---|---|
| Base SHA | dc82cb7e09a4c72aba05e0aba28edba3f5c1f3ac |
| Merged commit SHA | to be recorded after merge |
| Independent approval | Pending |

## Controller inventory and service authorization

Service-level rules are in the referenced controllers and security configurations. S1 and S2 use the shared security starter (authenticated by default plus method rules); S3/S4 have their own authenticated default chains; S5 uses the starter. All services allow their own health/docs directly; those paths are not exposed as upstream routes by the gateway.

| Controller / owning service | Service-level rule for inventory rows |
|---|---|
| S1 AdminCourierController, AdminMerchantApplicationController, AdminUserController | DISPATCHER; platform management |
| S1 CourierSelfProfileController | COURIER; JWT subject scopes profile/provisioning |
| S1 MerchantApiKeyController | MERCHANT; own account; active/approved checks for issuance |
| S1 MerchantApplicationController | Any human for own application GET; CUSTOMER for POST |
| S1 UserProfileController | Any human; subject/owner-or-DISPATCHER check for user-id access |
| S2 MerchantOrderController | MERCHANT plus approved/active status; JWT subject scopes list/read/write |
| S2 ParcelBookingController | CUSTOMER; JWT subject scopes booking |
| S2 ParcelStatusController | DISPATCHER or SERVICE directly; gateway deliberately excludes SERVICE |
| S2 AdminPricingRuleController | DISPATCHER |
| S2 ParcelPricingController | CUSTOMER or DISPATCHER |
| S2 ZoneController | Any human |
| S3 CourierDeliveryController | COURIER plus assigned-delivery ownership |
| S3 DispatchQueueController | DISPATCHER plus DISPATCH_VIEW on GETs, DISPATCH_ASSIGN on assignment POST (merged #133) |
| S4 CustomerTrackingController | CUSTOMER/MERCHANT; subject-scoped mine list |
| S4 PublicTrackingController | Anonymous/non-SERVICE-only GET reference/events/stream; public projection |
| S5 IntegrationAttemptController | DISPATCHER |
| S5 MerchantWebhookController | MERCHANT; own merchant data and applicable approved/active checks |

The following complete method/path listing was generated by GatewayInventoryTests; each row inherits the service rule from its controller above. Detailed ownership evidence remains in `docs/security/permission-matrix.md`; its S3 permission notes predate #133, so the current source rules above take precedence.

| Service | Method | Path | Effective route | Source |
|---|---|---|---|---|
| identity-partner-service | GET | `/api/v1/admin/couriers` | identity | AdminCourierController.java | 
| identity-partner-service | GET | `/api/v1/admin/couriers/{courierId}` | identity | AdminCourierController.java | 
| identity-partner-service | PATCH | `/api/v1/admin/couriers/{courierId}` | identity | AdminCourierController.java | 
| identity-partner-service | GET | `/api/v1/courier/profile` | identity | CourierSelfProfileController.java | 
| identity-partner-service | GET | `/api/v1/users/courier-profile` | identity | CourierSelfProfileController.java | 
| identity-partner-service | POST | `/api/v1/courier/profile/provision` | identity | CourierSelfProfileController.java | 
| identity-partner-service | POST | `/api/v1/users/courier-profile/provision` | identity | CourierSelfProfileController.java | 
| identity-partner-service | GET | `/api/v1/admin/merchants` | identity | AdminMerchantApplicationController.java | 
| identity-partner-service | PATCH | `/api/v1/admin/merchants/{applicationId}` | identity | AdminMerchantApplicationController.java | 
| identity-partner-service | PATCH | `/api/v1/admin/merchants/{applicationId}/suspend` | identity | AdminMerchantApplicationController.java | 
| identity-partner-service | PATCH | `/api/v1/admin/merchants/{applicationId}/reinstate` | identity | AdminMerchantApplicationController.java | 
| identity-partner-service | GET | `/api/v1/admin/merchants/{applicationId}/api-key` | identity | AdminMerchantApplicationController.java | 
| identity-partner-service | DELETE | `/api/v1/admin/merchants/{applicationId}/api-key` | identity | AdminMerchantApplicationController.java | 
| identity-partner-service | GET | `/api/v1/merchants/api-key` | identity | MerchantApiKeyController.java | 
| identity-partner-service | POST | `/api/v1/merchants/api-key` | identity | MerchantApiKeyController.java | 
| identity-partner-service | DELETE | `/api/v1/merchants/api-key` | identity | MerchantApiKeyController.java | 
| identity-partner-service | GET | `/api/v1/merchants/me` | identity | MerchantApplicationController.java | 
| identity-partner-service | POST | `/api/v1/merchants` | identity | MerchantApplicationController.java | 
| identity-partner-service | GET | `/api/v1/admin/users` | identity | AdminUserController.java | 
| identity-partner-service | GET | `/api/v1/admin/users/{userId}` | identity | AdminUserController.java | 
| identity-partner-service | PATCH | `/api/v1/admin/users/{userId}` | identity | AdminUserController.java | 
| identity-partner-service | GET | `/api/v1/users/me` | identity | UserProfileController.java | 
| identity-partner-service | PATCH | `/api/v1/users/me` | identity | UserProfileController.java | 
| identity-partner-service | GET | `/api/v1/users/{userId}` | identity | UserProfileController.java | 
| identity-partner-service | PATCH | `/api/v1/users/{userId}` | identity | UserProfileController.java | 
| parcel-pricing-service | POST | `/api/v1/merchant/orders` | parcel | MerchantOrderController.java | 
| parcel-pricing-service | GET | `/api/v1/merchant/orders/{id}` | parcel | MerchantOrderController.java | 
| parcel-pricing-service | GET | `/api/v1/merchant/orders` | parcel | MerchantOrderController.java | 
| parcel-pricing-service | POST | `/api/v1/parcels` | parcel | ParcelBookingController.java | 
| parcel-pricing-service | PATCH | `/api/v1/parcels/{parcelId}/status` | parcel | ParcelStatusController.java | 
| parcel-pricing-service | GET | `/api/v1/admin/pricing-rules` | parcel | AdminPricingRuleController.java | 
| parcel-pricing-service | PUT | `/api/v1/admin/pricing-rules` | parcel | AdminPricingRuleController.java | 
| parcel-pricing-service | POST | `/api/v1/parcels/estimate` | parcel | ParcelPricingController.java | 
| parcel-pricing-service | POST | `/api/v1/parcels/estimate-fee` | parcel | ParcelPricingController.java | 
| parcel-pricing-service | GET | `/api/v1/zones` | parcel | ZoneController.java | 
| dispatch-courier-service | GET | `/api/v1/courier/deliveries` | dispatch | CourierDeliveryController.java | 
| dispatch-courier-service | GET | `/api/v1/courier/deliveries/{parcelId}` | dispatch | CourierDeliveryController.java | 
| dispatch-courier-service | GET | `/api/v1/courier/deliveries/{parcelId}/proof` | dispatch | CourierDeliveryController.java | 
| dispatch-courier-service | POST | `/api/v1/courier/deliveries/{parcelId}/proof` | dispatch | CourierDeliveryController.java | 
| dispatch-courier-service | GET | `/api/v1/dispatch/queue` | dispatch | DispatchQueueController.java | 
| dispatch-courier-service | GET | `/api/v1/dispatch/unassigned` | dispatch | DispatchQueueController.java | 
| dispatch-courier-service | GET | `/api/v1/dispatch/eligible-couriers` | dispatch | DispatchQueueController.java | 
| dispatch-courier-service | GET | `/api/v1/dispatch/workload` | dispatch | DispatchQueueController.java | 
| dispatch-courier-service | POST | `/api/v1/dispatch/assignments` | dispatch | DispatchQueueController.java | 
| tracking-reporting-service | GET | `/api/v1/tracking/mine` | tracking | CustomerTrackingController.java | 
| tracking-reporting-service | GET | `/api/v1/tracking/{trackingRef}` | tracking | PublicTrackingController.java | 
| tracking-reporting-service | GET | `/api/v1/tracking/{trackingRef}/events` | tracking | PublicTrackingController.java | 
| tracking-reporting-service | GET | `/api/v1/tracking/{trackingRef}/stream` | tracking | PublicTrackingController.java | 
| notification-integration-service | GET | `/api/v1/admin/notification-attempts` | notification | IntegrationAttemptController.java | 
| notification-integration-service | GET | `/api/v1/admin/webhook-deliveries` | notification | IntegrationAttemptController.java | 
| notification-integration-service | POST | `/api/v1/merchant/webhooks` | notification | MerchantWebhookController.java | 
| notification-integration-service | GET | `/api/v1/merchant/webhooks` | notification | MerchantWebhookController.java | 
| notification-integration-service | DELETE | `/api/v1/merchant/webhooks` | notification | MerchantWebhookController.java | 
| notification-integration-service | POST | `/api/v1/merchant/webhooks/secret` | notification | MerchantWebhookController.java | 
| notification-integration-service | GET | `/api/v1/merchant/webhooks/deliveries` | notification | MerchantWebhookController.java | 

### Source evidence index

- Routes/targets/timeouts: `backend/api-gateway/src/main/resources/application.yml:13`.
- Ordered gateway authorization and fallback: `backend/api-gateway/src/main/java/com/zentro/gateway/config/GatewaySecurityConfig.java:47`.
- Internal/non-API interception: `backend/api-gateway/src/main/java/com/zentro/gateway/filters/NonApiNotFoundFilter.java:16`.
- CORS request/exposure policy: `backend/api-gateway/src/main/java/com/zentro/gateway/config/CorsConfig.java:18`.
- Identity header removal: `backend/api-gateway/src/main/java/com/zentro/gateway/filters/IdentityHeaderSanitizingFilter.java:13`.
- Local safe errors: `backend/api-gateway/src/main/java/com/zentro/gateway/exception/ProblemDetailHandlers.java:22` and `ProblemWriter.java:16`.
- Sanitizer: `backend/api-gateway/src/main/java/com/zentro/gateway/filters/UpstreamResponseSanitizingFilter.java:28`.
- Correlation coverage: `backend/api-gateway/src/main/java/com/zentro/gateway/filters/CorrelationIdFilter.java:15`.
- Rate limits/proxy-IP trust: `backend/api-gateway/src/main/java/com/zentro/gateway/filters/RateLimitFilter.java:29`.
- All controller files/methods: generated inventory above, under each service's `src/main/java`; service authorization table plus source annotations and owning security configurations.

### Actual environment

```text
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment (build 21.0.12.1+1-1-24.04.4-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.12.1+1-1-24.04.4-Ubuntu, mixed mode, sharing)
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.12.1, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en, platform encoding: UTF-8
OS name: "linux", version: "7.0.0-42-generic", arch: "amd64", family: "unix"
Docker version 29.8.0, build 88096ef
Linux 7.0.0-42-generic
```

These are actual java -version, mvn -v, docker --version and uname -sr outputs (ANSI decoration removed from Maven). Docker/Testcontainers reached the daemon and started Keycloak; the second run failed its readiness deadline, not a disabled-without-Docker skip.

### Actual commands so far

```bash
mvn --batch-mode --no-transfer-progress -f backend/pom.xml -pl api-gateway -am \
  -Dtest=GatewayKeycloakIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false test
# Executed twice, as independent invocations.
cd backend
mvn --batch-mode test package
```

| Invocation | Actual result |
|---|---|
| Focused gateway HTTP/config/filter tests | BUILD SUCCESS: 25 tests, 0 failures, 0 errors, 0 skipped; 19.552 s |
| Keycloak integration run 1 | BUILD SUCCESS: 146 tests, 0 failures, 0 errors, 0 skipped; suite 120.1 s, Maven 2:09 |
| Keycloak integration run 2 | BUILD FAILURE: 1 suite initialization error, 0 assertion failures, 0 skipped; suite 132.2 s, Maven 2:19. Keycloak readiness URL did not return 200 within the fixture's 120-second startup deadline; integration cases NOT RUN in this invocation |

The first integration run preceded the subsequently approved denyAll change. Final-source runs are recorded separately below. A failed repeat is retained as evidence; two clean repeat runs were not obtained at that stage; final-source results below retain this history.

### Acceptance evidence

| #144 criterion / audit requirement | Evidence | Status |
|---|---|---|
| Inspect existing work and reuse it | WARP.md; #26/#66/#25/#116/#146 (#146 PR #290 merged); existing fixture/security/filter design retained | Verified source inspection |
| Five services and database ownership | Five route rows and 55 controller mappings; only gateway/test/docs changes | Verified source/config; no service DB access |
| Correct prefixes, methods, targets and ordering | GatewayInventoryTests; generated forwarding test; existing Keycloak role matrix | Passed final full build |
| Default-deny/internal exposure | Local/internal negative HTTP tests; fallback SEC-1 | Approved and implemented; final-source tests passed |
| Explicit bounded configurable timeouts | YAML defaults, property override test, 504 bound/recovery/no-POST-retry test | Passed final full build; production budgets unverified |
| Safe error mapping | Existing refusal/timeout tests; new malformed HTTP/4xx tests; re-enabled shape test; early correlation tests | Passed covered cases; ERR-3 risk and ERR-4 shape gap remain |
| Identity/auth/resource ownership | Existing Keycloak role/header tests, new Host/forwarding probes; service-level source inventory | Passed gateway boundary; real service ownership covered by their suites, not stub proof |
| Request/header/pool limits | Source config and dependency javap defaults; existing oversized-header/1 MiB test | Risk reported; no new feature |
| Documentation | Real route/controller/timeout/error/findings tables and test-doc update | Updated |
| Gateway/full backend tests and repeated integration | Actual invocation results here and reactor results below | Final full build and two final-source integration runs passed; prior startup failure retained |
| Focused develop PR | PR linked from issue #144; Refs #144 while unmet | Draft; not merged |
| Exact merge SHA | to be recorded after merge | Not merged |
| Independent reviewer approval | kirusigan03 requested in GitHub Reviewers field | Pending review |

## Approved fail-closed change and frontend compatibility check

**Release note:** unlisted `/api/v1/**` paths now return 401/403 instead of 404 (deliberate fail-closed change); customer-portal real mode only works for estimate and booking. Explicit public tracking lookup/events/stream and gateway health remain anonymous; reserved reports retain DISPATCHER. Unserved non-API paths such as /internal, /error, Swagger/docs and /outside-api retain local 404. Encoded slash remains an existing firewall 400 exception, not an authorization bypass.

Read-only search covered customer, merchant, courier, dispatcher, public and shared modules for apiClient/fetch/URL constants and aliases. All active real calls use listed prefixes: users/merchants, courier profile aliases/deliveries, parcels estimate/booking, merchant orders/keys, dispatcher administration/dispatch/pricing/integration history, public tracking and its events transport. `/api/v1/parcels/estimate-fee` is covered by the parcel predicate even though the checked-in frontend uses /estimate. No active production call was found under an unlisted prefix; arbitrary environment overrides cannot be statically verified.

Dormant exception: `customer/services/config.ts:27` has baseUrl `/api/v1/customer`; `customer/services/customerApi.ts:448` can form `/api/v1/customer/parcels`. Real-mode calls under `/api/v1/customer/*` are not routed today, are off by default (`allowLegacyListParcelsRealCall=false`), and are enabled only by checked-in tests through setCustomerApiConfig with mocked HTTP. DenyAll changes their unrouted response from 404 to 401 anonymous / 403 authenticated. No working feature is affected; real mode is wired only for estimate and booking, with other customer features using mock adapters.

Tests were read, not executed with Node:
- `frontend/zentro-web/src/portals/customer/services/__tests__/customerApi.test.ts:365` enables legacy listParcels and uses `vi.spyOn(apiClient, 'get').mockResolvedValueOnce(...)` at line 383. Its custom deliveriesEndpoint case at line 398 also mocks apiClient.get and points to already-routed /api/v1/tracking/mine. Neither reaches a real gateway.
- `frontend/zentro-web/src/portals/customer/pages/__tests__/SendParcelPage.test.tsx` invokes setCustomerApiConfig for mock resets and estimate/booking real-mode scenarios, using MSW HTTP handlers; it does not enable the legacy list path. Shared `testing/setup.ts:6` sets MSW onUnhandledRequest='error'.

### Frontend follow-up issue text (not implemented)

**Title:** Remove or gate dormant customer legacy real-mode API calls until contracts exist

**Owner:** @pirathee587, verified as the recent author in git history for customer/services/customerApi.ts and config.ts (other historical authors were also present).

**Problem:** Legacy /api/v1/customer/* calls have no gateway route or approved backend contract. Their test-only configuration escape hatch can obscure that customer real mode supports estimate/booking only. The approved fail-closed gateway now rejects unrouted calls with 401/403.

**Next action / acceptance:** Remove or explicitly gate these real-mode calls until contracts are approved; show a clear “not available yet” UI state instead of a forbidden or generic error. Add frontend tests for unavailable features and preserve estimate/booking real-mode behavior, idempotency and API error handling. Do not invent a backend endpoint or enable this path as part of #144.

### Superseded full build before approved fallback change

The first `cd backend && mvn --batch-mode test package` completed BUILD SUCCESS in 6:59. Gateway compilation/tests preceded the newly approved denyAll change; a second full build is therefore required for the final source and is recorded separately below. Counts from that first invocation (not stale XML):

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| zentro-security-starter | 7 | 0 | 0 | 0 |
| api-gateway | 190 | 0 | 0 | 0 |
| identity-partner-service | 336 | 0 | 0 | 0 |
| parcel-pricing-service | 628 | 0 | 0 | 0 |
| dispatch-courier-service | 181 | 0 | 0 | 0 |
| tracking-reporting-service | 48 | 0 | 0 | 0 |
| notification-integration-service | 380 | 0 | 0 | 0 |
| Total | 1770 | 0 | 0 | 0 |

The root aggregator has no tests. merchant-order-webhook-e2e packaged successfully but its two opt-in MerchantApprovalGapIT tests were NOT RUN: module POM sets skipTests=true unless the e2e/runE2E profile is selected. Maven's default test phase and package-bound test phase both print “Tests are skipped”; this is one opt-in group, not four skipped test cases. No stale E2E XML reports are counted. Gateway API-3's one previously disabled test is now enabled; Docker-backed tests in the ordinary modules ran without skip conditions in this invocation.

Development verification: the first new fallback-test invocation failed two assertions (15 tests, 2 failures): relative mock-client URLs made CORS origin checks malformed, and the encoded-slash probe correctly hit the existing firewall 400 before auth. The tests were corrected to use the real listening HTTP server for fallback/preflight and explicitly pin firewall rejection. The corrected GatewayDefaultDenyTests invocation passed 3 tests, 0 failures/errors/skips in 16.918 s. No gateway CORS or firewall policy was loosened.

**Integration startup follow-up — owner @pirathee587:** investigate the observed Keycloak readiness variance (one 120-second startup timeout), record host/container resource conditions and determine whether fixture startup budgeting needs adjustment. Later passing runs do not erase that failure or establish production reliability. No readiness timeout was increased and no integration case was disabled to obtain a pass.

## Final-source backend verification

`cd backend && mvn --batch-mode test package` completed **BUILD SUCCESS**, total Maven time **6:37**. All modules packaged successfully. These results include the approved denyAll fallback and all final gateway tests, including real HTTP negative/preflight cases and every-role real Keycloak fallback checks.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| zentro-security-starter | 7 | 0 | 0 | 0 |
| api-gateway | 193 | 0 | 0 | 0 |
| identity-partner-service | 336 | 0 | 0 | 0 |
| parcel-pricing-service | 628 | 0 | 0 | 0 |
| dispatch-courier-service | 181 | 0 | 0 | 0 |
| tracking-reporting-service | 48 | 0 | 0 | 0 |
| notification-integration-service | 380 | 0 | 0 | 0 |
| Total | 1773 | 0 | 0 | 0 |

The gateway's final full-build total includes **146 Keycloak integration tests, 0 failures/errors/skips, suite time 119.8 seconds** (final-source integration run 1). The remaining 47 are gateway unit/config/HTTP tests. The former API-3 skip is removed. Testcontainers-backed cases in these modules ran. Root aggregator: no tests. merchant-order-webhook-e2e: packaged, but **2 opt-in tests NOT RUN** for the same explicit skipTests/e2e-profile reason explained above; not counted as passing or Surefire-reported skips. No test failure was bypassed or fixture startup limit loosened.

Raw logs remain local: /tmp/144-backend-final.log, /tmp/144-integration-final-repeat.log; prior/development logs /tmp/144-backend-full.log, /tmp/144-integration-1.log, /tmp/144-integration-2.log, /tmp/144-gateway-focused.log, /tmp/144-deny-focused.log and /tmp/144-deny-final.log. Only aggregate results and source evidence are committed; raw logs are not published.

### Final-source integration run 2

The targeted repeat command shown above completed **BUILD SUCCESS: 146 tests, 0 failures, 0 errors, 0 skipped**, suite time **116.2 seconds**, Maven total **2:01**. Together with the 146-case final full-build invocation (119.8 seconds), the final source passed two integration runs. Both used disposable Keycloak and stub upstreams. The earlier pre-fallback Keycloak startup failure remains recorded and its follow-up remains open; these passes are not a claim of flake-free operation.

`git diff --check` passed. Draft/Refs #144 remains appropriate because the firewall empty-400 shape exception and streaming-error follow-up prevent a uniform-shape claim, opt-in cross-service E2E was not run, and independent acceptance is pending. No merge was performed.
