# ZD-072 internal call deadline and resilience audit (#147)

Owner: @14Jannan. Independent reviewer: @hephzibah-peries.
Scope: every internal synchronous call between the five services, plus the S1 gRPC server: deadlines and timeouts, behaviour when the callee is slow, down or failing, and retry limits (WARP.md 7.3, 14, 16). Gateway route timeouts were audited in #144; the webhook retry policy is #142.
Code audited: `develop` at 348a650. No production code was changed.

## 1. Result

**Every internal synchronous call has a connect and a read timeout, every failure is reported to the caller as a defined error or retryable state, and no call is retried without limit.** The only retry loop (S3 proof completion) is limited to 8 attempts with backoff. Tests against local stub servers prove each timeout with the real HTTP stack.

Two limits of the current timeouts are documented, not changed (decision D6):

- The read timeout limits each read, not the whole call (F1). A callee that sends its answer slowly in small parts can keep a call open past 5 s.
- The JDK `HttpURLConnection` used by three clients repeats an idempotent GET once when the connection drops before any reply (section 6). This is limited to one repeat and only affects read-only lookups.

**No gRPC client exists.** Only S1 runs a gRPC server and nothing calls it; the "gRPC" calls described in the README are REST calls (F2). The server was tested with a test client (section 5.1).

## 2. Inventory of internal synchronous calls

| # | Caller -> callee | Endpoint | HTTP stack | Connect / read timeout | Retries |
|---|---|---|---|---|---|
| 1 | S2 `integration/IdentityMerchantClient` -> S1 | GET `/api/v1/users/me` (merchant's token) | `SimpleClientHttpRequestFactory` (`HttpURLConnection`) | 3 s / 5 s per read | none (transport repeats a dropped GET once) |
| 2 | S5 `webhook/client/IdentityMerchantClient` -> S1 | GET `/api/v1/users/me` (merchant's token) | `SimpleClientHttpRequestFactory` | 3 s / 5 s per read | none (same) |
| 3 | S3 `courier/service/IdentityCourierClient` -> S1 | GET `/api/v1/users/courier-profile`, `/api/v1/admin/couriers[?active=true]`, `/api/v1/admin/couriers/{id}` (caller's token) | `SimpleClientHttpRequestFactory` | 3 s / 5 s per read | none (same) |
| 4 | S3 `courier/service/ParcelStatusClient` -> S2 | PATCH `/api/v1/parcels/{id}/status` (service token) | `JdkClientHttpRequestFactory` (JDK `HttpClient`) | 3 s / 5 s until the response headers | none per call |
| 5 | S3 `courier/service/ServiceTokenProvider` -> Keycloak | POST token endpoint (client credentials) | `JdkClientHttpRequestFactory` | 3 s / 5 s until the response headers | none per call; token cached |
| 6 | S3 `courier/service/ProofCompletionWorker` (drives 4 and 5) | — | — | — | at most `POD_MAX_ATTEMPTS` (default 8, allowed 1-20), backoff 5 s doubling to 300 s, then `BLOCKED RETRY_EXHAUSTED` |
| 7 | S1 gRPC server `IdentityPartnerService` (port `GRPC_SERVER_PORT`, 9081) | `GetMerchantStatus`, `GetCourierProfile` | grpc-java 1.68.0 (netty-shaded) | deadline set by the caller; no callers exist | grpc-java default: no retry policy |

Out of scope, listed for completeness: S5 `WebhookHttpSender` (external merchant endpoints; ZD-17/052, #142), the gateway (#144), Kafka. S1 and S4 make no outbound HTTP calls. The JWKS key download in the security starter is covered by F5.

## 3. Failure behaviour per call (verified by the tests in section 5)

| Callee behaviour | S2 merchant check (1) | S5 merchant check (2) | S3 courier lookups (3) | S3 status update (4) | S3 token (5) |
|---|---|---|---|---|---|
| Slow (6.5 s) | 503 after ~5 s | 503 `IDENTITY_UNAVAILABLE` after ~5 s | 503 after ~5 s | `PENDING PARCEL_SERVICE_UNAVAILABLE` after ~5 s | `PENDING TOKEN_SERVICE_UNAVAILABLE` after ~5 s, no PATCH sent |
| Connection refused | 503 at once | 503 at once | 503 at once | `PENDING` at once | `PENDING TOKEN_SERVICE_UNAVAILABLE` at once |
| Unreachable host | 503 within 3 s | 503 within 3 s | 503 within 3 s | `PENDING` within 3 s | — |
| 5xx / 429 | 503 | 503 | 503 | `PENDING` | `PENDING TOKEN_REQUEST_FAILED` |
| 401 | 401 | 403 `MERCHANT_NOT_APPROVED` | 401 | token dropped, `BLOCKED PARCEL_REQUEST_REJECTED`; next attempt fetches a new token | `BLOCKED TOKEN_REQUEST_FAILED` |
| 403 | 403 | 403 `MERCHANT_NOT_APPROVED` | 403 | `BLOCKED` | `BLOCKED` |
| 404 | 503 | 503 | 404 for `requireEligible`, otherwise 503 | `BLOCKED` | — |
| 409 | — | — | — | `REJECTED DELIVERY_TRANSITION_REJECTED` | — |
| Closed without reply | 503, 1-2 requests | 503, 1-2 requests | 503, 1-2 requests | `PENDING`, exactly 1 request | `PENDING`, exactly 1 request |
| Requests per call otherwise | exactly 1 | exactly 1 | exactly 1 | exactly 1 | exactly 1 (cached afterwards) |

Error bodies from the callee are never passed on, and no test found a token, client secret, name, phone number or email address in the logs.

`PENDING` proofs are retried by the worker (call 6) within its limit; `BLOCKED` and `REJECTED` are not retried.

## 4. Coverage before this audit

| Call | Existing tests | What they could not show |
|---|---|---|
| 1, 2 | Callers mock the client (`MerchantOrderControllerTest`, `MerchantWebhookApiTest`, ...) | The client itself: no timeout, status or request-count tests |
| 3 | `IdentityCourierClientTest` (`MockRestServiceServer`) | Real timeouts: the mock server replaces the HTTP stack, so the 3 s / 5 s settings never run |
| 4 | `ParcelStatusClientTest` (`MockRestServiceServer`, simulated `IOException`) | Same |
| 5 | `ServiceTokenProviderTest` (`MockRestServiceServer`) | Same |
| 6 | `ProofCompletionWorkerTest` | Complete (backoff, limit, no retry for business errors) |
| 7 | `IdentityPartnerGrpcServerIntegrationTest` | Authentication only; no deadline, unavailability, error or retry tests |

## 5. Tests added

Every HTTP test builds the client with its **production constructor**, so the real request factory and timeouts are used, and talks to `StubHttpServer`. This is a small `com.sun.net.httpserver` stub on a random local port that can answer late, send the body in slow parts, return any status or close the connection without answering, and counts requests. One copy per service (WARP: no cross-service imports). Timing limits are generous: a slow call must fail between 4.5 s and 7 s; a refused connection under 2.5 s; an unreachable host under 5 s.

### 5.1 S1 gRPC server: `identity-partner-service/.../grpc/IdentityPartnerGrpcResilienceIntegrationTest` (7 tests)

The tests use the real `IdentityPartnerGrpcServer`, `GrpcSecurityInterceptor` and `IdentityPartnerGrpcService` on a random port, with a plain blocking stub. The database and courier service are mocked.

| Test | Proves |
|---|---|
| `shortDeadlineAgainstSlowQueryFailsWithDeadlineExceeded` | 300 ms deadline vs a 3 s query: `DEADLINE_EXCEEDED` in 250 ms-2 s; the deadline reaches the server and its context is cancelled; the worker thread is not interrupted (F3); one query |
| `stoppedServerReturnsUnavailableQuickly` | `UNAVAILABLE` in under 3 s even with a 10 s deadline |
| `nothingListeningReturnsUnavailable` | `UNAVAILABLE` in under 3 s |
| `databaseFailureIsNotRetriedAndDoesNotLeakDetails` | `UNKNOWN` (no mapping exists, F3), no host or port in the description, exactly one query |
| `failingCourierLookupIsNotRetried` | `UNKNOWN`, exactly one call |
| `blankMerchantIdReturnsNotFoundWithoutDatabase` | Blank ids return `NOT_FOUND` without a query (no `INVALID_ARGUMENT`, F3) |
| `alreadyExpiredDeadlineNeverReachesTheService` | `DEADLINE_EXCEEDED`; neither the database nor the courier service is called |

### 5.2 S2 -> S1: `parcel-pricing-service/.../integration/IdentityMerchantClientResilienceTest` (15 tests)
Approved merchant (1 request, caller's token forwarded); slow -> 503 in 4.5-7 s; refused -> 503; unreachable -> 503 under 5 s; 500/502/503/504/429/404 -> 503 with 1 request and no dependency text; 401/403 kept; closed without reply -> 503 with at most 2 requests; slow parts -> succeeds after more than 5 s (F1); no token or personal data in logs.

### 5.3 S3 -> S1: `dispatch-courier-service/.../courier/service/IdentityCourierClientResilienceTest` (24 tests)
For each of `currentCourier`, `listEligible`, `listAll`, `requireEligible`: healthy (1 request, token forwarded), slow -> 503 in 4.5-7 s, refused -> 503. Plus unreachable -> 503; 5xx/429 -> 503 with 1 request; 401/403 kept; 404 -> 404 only for `requireEligible`; closed without reply -> at most 2 requests; slow parts (F1); no token or courier name in logs.

### 5.4 S3 -> S2 and Keycloak: `ParcelStatusClientResilienceTest` (21 tests) and `ServiceTokenProviderResilienceTest` (14 tests)

- **Status client:** 1 token request and 1 PATCH; cached token reused for 3 completions; slow S2 -> `PENDING` in 4.5-7 s; S2 down or unreachable -> `PENDING`; 429/5xx -> `PENDING` with 1 request; 409 -> `REJECTED`; 400/403/404/422 -> `BLOCKED`; 401 drops the token and the next attempt fetches a new one; closed without reply -> exactly 1 PATCH; slow or down token endpoint -> `PENDING TOKEN_SERVICE_UNAVAILABLE` and no PATCH sent; slow body parts (F1); no token or secret in logs.
- **Token provider:** cached until shortly before expiry (1 request for 3 calls); tokens living 30 s or less are fetched on every call (30 s safety margin); slow -> `PENDING` in 4.5-7 s; down -> `PENDING`; 429/5xx -> `PENDING` with 1 request; 400/401/403 -> `BLOCKED`; closed without reply -> exactly 1 POST; a failed request is not cached and the next call recovers; the client secret and token are never logged.

### 5.5 S5 -> S1: `notification-integration-service/.../webhook/client/IdentityMerchantClientResilienceTest` (15 tests)
The same scenarios as 5.2, with S5's codes: `IDENTITY_UNAVAILABLE` (503) and `MERCHANT_NOT_APPROVED` (403 for 401/403).

## 6. Transport behaviour (JDK 21, measured)

A JDK-only probe and the tests above show:

| Callee behaviour | `HttpURLConnection` (calls 1-3) | JDK `HttpClient` (calls 4-5) |
|---|---|---|
| Closes the connection without replying | GET and POST sent **twice** (one automatic repeat) | GET twice; POST and PATCH **once** |
| Headers at once, then 1 body byte per second for 8 s, 3 s read timeout | completes after 8.1 s | completes after 8.1 s |

The automatic repeat only affects the read-only GET lookups (calls 1-3) and is limited to one, so it is not a defect. The status PATCH and the token POST are never repeated by the transport.

## 7. Test results (actual commands)

`mvn --batch-mode --no-transfer-progress -pl <module> -am test` from `backend/`, after each step:

| Module | Before | After | Result |
|---|---|---|---|
| identity-partner-service | 336 | 343 (+7) | 0 failures, 0 skipped |
| parcel-pricing-service | 628 | 643 (+15) | 0 failures, 0 skipped |
| dispatch-courier-service | 192 | 251 (+59) | 0 failures, 0 skipped |
| notification-integration-service | 380 | 395 (+15) | 0 failures, 0 skipped |

Notes:

- During one local S3 run, a single slow-call case (`requireEligible`) took 96 s instead of about 5 s. It did not happen again in four further runs (16 slow calls, each 5.0 s). The test limits were not widened.
- The baseline S2 run on `develop` had 2 failures in `ParcelStatusIntegrationTest` (F8). Rerunning that class gave 2 failures in 3 runs; it passed in the later runs.

## 8. Findings and follow-ups

| # | Finding | Owner | Next action |
|---|---|---|---|
| F1 | Read timeouts limit each read (calls 1-3) or the time to the response headers (calls 4-5), not the whole call. A callee that sends its answer slowly keeps the call open past 5 s. All callees are our own Spring services that send small answers at once, so the risk is low. | client owners: S2 @Kiruthiyan, S3 @pavi3alnx / @Nilakshana23, S5 @14Jannan | Follow-up issue: add a total time limit per call |
| F2 | README line 61 says "gRPC: three internal synchronous calls (S2 -> S1, S3 -> S1, S3 -> S2)" and lines 47-49 / 82-84 list gRPC ports 9081-9083. All three calls are REST; 9082 and 9083 have no server (`parcel_pricing.proto` has no implementation). WARP 7.3 also names gRPC for internal calls. | @pirathee587 (README, architecture) | Decide: correct the README to REST, or plan a migration (architecture change, all owners) |
| F3 | S1 gRPC server: exceptions return `UNKNOWN` instead of `UNAVAILABLE` / `INTERNAL`; blank or malformed ids return `OK` (`NOT_FOUND` / `active=false`) instead of `INVALID_ARGUMENT`; work is not stopped when the caller's deadline expires, and the JDBC query has no timeout. No callers exist today. | @kirusigan03 | Add status mapping and cancellation checks before the first gRPC client is added (contract change for invalid ids needs review) |
| F4 | The 3000 / 5000 ms timeouts are hard-coded in five clients and cannot be configured. | client owners | Move to configuration when F1 is addressed |
| F5 | The security starter's `NimbusJwtDecoder` downloads JWKS keys with the default `RestTemplate`, which has no connect or read timeout. Keys are cached, so this affects key refreshes. | @pirathee587 | Configure timeouts on the JWKS client |
| F6 | The S1 internal token has a hard-coded default `zentro-internal-service-secret` in `application.yml` (line 19) and in `GrpcSecurityInterceptor` (line 39); the token is compared with `String.equals`. | @kirusigan03 | Remove the defaults (fail at start-up when unset); use a constant-time comparison |
| F7 | No tracing context is propagated on any internal call (WARP 7.3 / 17); no tracing library is configured. | architecture owners | Separate observability issue |
| F8 | `ParcelStatusIntegrationTest.fullLifecycle` / `failedPathByDispatcherAndServiceRejected` are flaky on Windows: the clock moves in steps of up to 15.6 ms, so two quick status changes can share a timestamp, and the test then orders by a random UUID. | @Jathuja / @pirathee587 | Order by a stable sequence in the test |

## 9. Full build

`mvn --batch-mode --no-transfer-progress test package` from `backend/` (all modules, Docker running), actual summary:

```
Zentro Security Starter ............ SUCCESS   Tests run: 7,   Failures: 0, Errors: 0, Skipped: 0
api-gateway ........................ SUCCESS   Tests run: 193, Failures: 0, Errors: 0, Skipped: 0
identity-partner-service ........... SUCCESS   Tests run: 343, Failures: 0, Errors: 0, Skipped: 0
parcel-pricing-service ............. SUCCESS   Tests run: 643, Failures: 0, Errors: 0, Skipped: 0
dispatch-courier-service ........... SUCCESS   Tests run: 251, Failures: 0, Errors: 0, Skipped: 0
tracking-reporting-service ......... SUCCESS   Tests run: 48,  Failures: 0, Errors: 0, Skipped: 0
notification-integration-service ... SUCCESS   Tests run: 395, Failures: 0, Errors: 0, Skipped: 0
merchant-order-webhook-e2e ......... SUCCESS   (compiled; its end-to-end tests run separately)
BUILD SUCCESS (10:54 min)
```

An earlier full run stopped in `api-gateway`: `GatewayKeycloakIntegrationTests.expiredKeycloakTokenIsUnauthorizedWithoutForwarding` waited 75 s for a token to expire inside the Keycloak container and timed out, and the next test in the class then received 401. The gateway is not changed by this work; the module passed (193/193) on the next two runs.
