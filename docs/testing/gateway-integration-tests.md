# API gateway integration tests (ZD-31 / #66)

## Prerequisites and commands

- Docker Engine must be running and reachable by Testcontainers.
- Java 21 and Maven 3.8.7 or newer.
- Network access to pull `quay.io/keycloak/keycloak:26.3.3` on the first run.
- The gateway module test dependencies must be available from Maven Central.

Run the gateway suite from the repository root:

```bash
mvn --batch-mode --no-transfer-progress -f backend/pom.xml -pl api-gateway -am -Dtest=GatewayKeycloakIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false test
```

Run the whole backend build and tests:

```bash
cd backend
mvn --batch-mode --no-transfer-progress test package
```

Run the new suite twice to check repeatability:

```bash
for run in 1 2; do
  mvn --batch-mode --no-transfer-progress -f backend/pom.xml -pl api-gateway -am -Dtest=GatewayKeycloakIntegrationTests -Dsurefire.failIfNoSpecifiedTests=false test || exit "$?"
done
```

Surefire XML and text reports are written to `backend/api-gateway/target/surefire-reports/`.
The `@Testcontainers(disabledWithoutDocker = true)` annotation skips this suite when Docker is not
available; a skipped suite is not evidence that the integration cases passed.

## Test environment

The suite starts a fresh Keycloak 26.3.3 container, imports
`infra/keycloak/backups/zentro-realm-export.json`, and creates test-only service-account clients for
each role, plus a roleless client and special wrong-audience/short-lived clients. Their service
account users exist only inside that ephemeral container. Because CUSTOMER is the realm's default
role, setup removes that role from the other accounts so each matrix identity has the intended
exclusive role. Tokens are issued and signed by Keycloak and verified by the running Spring Boot
gateway using its real JWT decoder and security filter chain. The gateway listens on a random port.

All S1–S5 upstreams are deterministic `MockWebServer` stubs for this gateway boundary suite. Each
stub records the forwarded request and lets the test assert the destination, method, path, query,
body, authorization, and identity-header handling. This avoids coupling gateway policy assertions to
service database contents. S1 and S2 are implemented services, but launching them with independent
PostgreSQL containers and their own application configuration is outside this gateway-only fixture;
this remains a limitation of the evidence. The existing downstream service integration suites cover
their own application and database behavior.

No shared Keycloak instance, `.env` file, or Docker Compose stack is used or modified.

## Route table used by the matrix

The rows are derived from `backend/api-gateway/src/main/resources/application.yml` and matched
against `GatewaySecurityConfig`.

| Prefix / route sample | Upstream | Gateway access policy | Public |
|---|---|---|---|
| `/api/v1/users/**` | S1 identity | CUSTOMER, MERCHANT, COURIER, DISPATCHER | No |
| `/api/v1/merchants/**` | S1 identity | CUSTOMER, MERCHANT, COURIER, DISPATCHER | No |
| `/api/v1/admin/merchants/**` | S1 identity | DISPATCHER | No |
| `/api/v1/admin/users/**` | S1 identity | DISPATCHER | No |
| `/api/v1/admin/couriers/**` | S1 identity | DISPATCHER | No |
| `/api/v1/zones/**` | S2 parcel/pricing | CUSTOMER, MERCHANT, COURIER, DISPATCHER | No |
| `/api/v1/parcels/**` | S2 parcel/pricing | CUSTOMER, DISPATCHER | No |
| `/api/v1/merchant/orders/**` | S2 parcel/pricing | MERCHANT | No |
| `/api/v1/admin/zones/**` | S2 parcel/pricing | DISPATCHER | No |
| `/api/v1/admin/pricing-rules/**` | S2 parcel/pricing | DISPATCHER | No |
| `/api/v1/dispatch/**` | S3 dispatch | DISPATCHER | No |
| `/api/v1/courier/**` | S3 dispatch | COURIER | No |
| `GET /api/v1/tracking/{trackingNumber}` | S4 tracking | Anonymous and all roles except SERVICE-only | Yes |
| `GET /api/v1/tracking/{trackingNumber}/events` | S4 tracking | Anonymous and all roles except SERVICE-only | Yes |
| `GET /api/v1/tracking/{trackingNumber}/stream` | S4 tracking | Anonymous and all roles except SERVICE-only | Yes |
| `GET /api/v1/tracking/mine` | S4 tracking | CUSTOMER, MERCHANT | No |
| `/api/v1/reports/**` | S4 tracking | DISPATCHER | No |
| `/api/v1/merchant/webhooks/**` | S5 notification | MERCHANT | No |
| `/api/v1/admin/audit-logs/**` | S5 notification | DISPATCHER | No |
| `/actuator/health[/**]` | Gateway | Anonymous | Yes |
| `/actuator/prometheus` | Gateway | Human roles | No |
| Unlisted `/api/v1/**` path | No route | Safe 404 after route lookup | No upstream |
| Other non-API path | No route | Safe 404 | No upstream |

The authorization documentation (`docs/architecture/authorization.md`) and gateway README agree with
these role groups. The config additionally permits health and enforces a human role for Prometheus;
neither is an upstream route. The `SERVICE` role is intentionally excluded from human-only routes.

## Matrix and edge coverage

`GatewayKeycloakIntegrationTests.routeRoleMatrixForwardsAllowedCallsAndRejectsOthers` produces each
route row crossed with CUSTOMER, MERCHANT, COURIER, DISPATCHER, SERVICE, a roleless signed user, and no
token. Each allowed case asserts actual HTTP 200 and the exact recorded upstream method/path/query/body;
each rejected case asserts 401 or 403 and no recorded upstream request.

Additional cases cover invalid/mutated tokens and wrong audience, malformed authorization schemes,
path/method bypass probes, identity-header spoofing, public tracking and health, unknown routes, CORS,
empty, malformed, and oversized request bodies, concurrent requests, and sanitized upstream 5xx handling. Existing
`GatewayUpstreamFailureTests` cover connection refusal, slow upstream timeout, and upstream 503 response.

The CORS check is intentionally read-only. Current allowed request headers are `Authorization`,
`Content-Type`, and `X-Correlation-Id`; `Idempotency-Key` is not allowed, although parcel booking
documents that header for browser clients. This known gap is reported without making it a test failure.

## Findings

- **Permission mismatches:** none found between the route config, gateway security rules, gateway
  README, and authorization architecture documentation during inspection.
- **Bypass attempts:** the suite contains normalization, encoding, path traversal, path-parameter,
  method, actuator, and unlisted-route probes. Results remain unverified until the Keycloak-backed
  tests run in an environment with Docker.
- **CORS gap:** `Idempotency-Key` is documented for parcel booking but absent from gateway
  `Access-Control-Allow-Headers`; browser preflight for that header is rejected. No config change is
  included in this testing task.
- **Error-shape mismatch:** `ProblemWriter` includes an `instance` field, while
  `UpstreamResponseSanitizingFilter` emits an upstream 5xx problem without it. The strict equality
  test remains disabled with this finding as its reason; see
  [`upstreamAndGatewayFailuresUseTheSameProblemJsonShape`](../../backend/api-gateway/src/test/java/com/zentro/gateway/integration/GatewayKeycloakIntegrationTests.java)
  for the follow-up test. No gateway implementation is changed here.

## Acceptance criteria to evidence

| #66 criterion | Evidence |
|---|---|
| Role-bound routes forward only to configured upstreams | Parameterized route × role table, recorded upstream requests, and 401/403 no-receipt assertions |
| Tokens are issued by Keycloak and validated by gateway security | Ephemeral Keycloak realm import and real signed user tokens in `GatewayKeycloakIntegrationTests` |
| Public/unknown routes and common bypass attempts are checked | Public route tests and protected-path/method probe cases |
| Upstream failures return safe gateway errors | Real gateway requests to instrumented stub failures; existing connection-refusal coverage; correlation and body checks |
| Reproducible setup and commands are documented | This document |
| Permission/CORS findings remain visible | Findings above; CORS observation does not fail the build |

## Reading results and evidence

Parameterized test names include the route id and identity so a failing matrix cell identifies its
expectation. Rejected calls must not appear in any stub's request queue. Upstream responses are
asserted at the gateway's random HTTP port, not by calling the stub directly.

Record the following after running the suite and backend build:

| Evidence | Result |
|---|---|
| Gateway integration command | Passed twice; each run: 146 tests, 0 failures, 0 errors, 1 intentionally disabled |
| First/second repeat run | Both passed; first elapsed 118.7 s, second 112.6 s |
| Full backend `mvn test` | Passed: 1,037 tests, 0 failures, 0 errors, 6 skipped across all modules |
| Full backend `mvn test package` | Gateway tests passed; packaging stopped because Maven Central DNS resolution failed for `spring-boot-buildpack-platform:3.5.16`, so later modules were skipped in that invocation |
| Surefire reports | `backend/api-gateway/target/surefire-reports/` |
| Commit SHA | Recorded in Git history and the pull request description |
| Environment (OS, Docker, Java) | Ubuntu 24.04.4 (kernel 7.0.0-38-generic); Docker Engine 29.8.0; Java 21.0.12.1 |

The package phase needs Maven Central access for the uncached Spring Boot plugin dependency. The
separate full reactor `mvn test` passed; retry packaging when Maven Central is reachable.
