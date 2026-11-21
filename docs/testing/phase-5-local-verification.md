# Phase 5 Local Verification

Verification run on 2026-11-18 against `feature/phase-5-distributed-integration` at `a9b00f0` (equal to `origin/main` at the time), plus the uncommitted `docs/api/build_openapi.py` change described below. It records what was actually executed on a low-memory Windows developer laptop, what was deliberately not run, and the procedure for repeating it on another machine.

A result marked **PASS** was confirmed by a zero exit code and Maven `BUILD SUCCESS` or Vitest's summary. **NOT RUN** means not executed; it is not a pass.

## Summary

| Area | Result |
|---|---|
| OpenAPI and gateway consistency (`build_openapi.py --check`) | PASS after the internal-route fix below |
| `docker compose config --quiet` | PASS (exit 0) |
| Backend unit and slice tests (starter, S1–S5, gateway) | PASS: 2,101 tests, 0 failures |
| Backend Testcontainers tests (all 42 classes) | PASS: 505 tests, 0 failures |
| Frontend unit tests (Vitest) | PASS: 72 files, 844 tests, 0 failures |
| Frontend E2E (Playwright, 8 specs) | NOT RUN |
| Distributed POD workflow (`DistributedPodWorkflowIT`) | NOT RUN |
| Runtime Compose stack with provisioned service clients | NOT RUN |

## Environment

| Item | Value |
|---|---|
| OS | Windows 11 Pro 10.0.26300, x64 |
| CPU / RAM | Intel i5-1235U (10 cores), 15.6 GB RAM; about 3.3–4.8 GB free during testing |
| Docker | Docker Desktop 29.9.2 (WSL 2 backend), Compose v5.6.1, about 7.6 GB available to Docker |
| Java / Maven | JDK 21.0.4, Maven 3.9.11 |
| Node / npm | Node 22.19.0, npm 10.9.3, Vitest 2.1.9, MSW 2.15.0 |
| Python | 3.13 (the `python3` alias was unavailable; `python` was used) |

## OpenAPI and gateway consistency

`python docs/api/build_openapi.py --check` initially failed on 11 controller mappings under `/internal/*` in S1, S2 and S3. These are service-to-service REST endpoints called with user-role tokens (COURIER or DISPATCHER), are intentionally absent from the public gateway contract, and are already approved one by one in `GatewayInventoryTests.INTERNAL`. The four assistant routes previously reported missing were already fixed on `main`.

The checker now keeps an exact `INTERNAL` allowlist that mirrors `GatewayInventoryTests`. It excludes those mappings from the public-contract comparison and fails when:

- an approved internal mapping is matched by any public gateway route, or
- an approved internal mapping no longer exists in a controller.

An `/internal` prefix alone is not approval. Both guards were confirmed by injecting each fault in memory. After the change the check prints `OpenAPI validity and controller mapping completeness: PASS (5 specs)`. No gateway route or specification was changed, and no internal endpoint is exposed.

## Backend results

All runs used `MAVEN_OPTS=-Xmx1g`, `-T1` and one Maven build at a time. Every command runs from the repository root.

### Unit and slice tests

Testcontainers classes were excluded with `-Dtest=!ClassA,!ClassB,...` and run separately.

| Suite | Tests | Failures | Errors | Skipped | Maven time |
|---|---|---|---|---|---|
| `zentro-security-starter` | 63 | 0 | 0 | 0 | 23.7 s |
| S1 `identity-partner-service` | 437 | 0 | 0 | 0 | 42.8 s |
| S2 `parcel-pricing-service` | 638 | 0 | 0 | 0 | 66 s |
| S3 `dispatch-courier-service` | 233 | 0 | 0 | 0 | 46.0 s |
| S4 `tracking-reporting-service` | 212 | 0 | 0 | 0 | 39.6 s |
| S5 `notification-integration-service` | 448 | 0 | 0 | 0 | 49.9 s |
| `api-gateway` | 70 | 0 | 0 | 0 | 44.0 s |
| **Total** | **2,101** | **0** | **0** | **0** | |

### Testcontainers tests

Each run used disposable `postgres:16-alpine`, `confluentinc/cp-kafka:7.6.1` or `quay.io/keycloak/keycloak:26.3.3` containers. No more than three containers ran at once.

| Module | Classes | Tests | Failures | Maven time | Lowest free RAM |
|---|---|---|---|---|---|
| S1 | 2 | 23 | 0 | 33.5 s | 1.89 GB |
| S2 | 16 | 116 | 0 | 2:46 min | 2.38 GB |
| S3 | 6 | 91 | 0 | 2:07 min | 0.94 GB (Keycloak start) |
| S4 | 7 | 63 | 0 | 1:20 min | 2.13 GB |
| S5 | 10 | 66 | 0 | 4:15 min | 1.65 GB |
| `api-gateway` (`GatewayKeycloakIntegrationTests`) | 1 | 146 | 0 | 2:50 min | 2.28 GB |
| **Total** | **42** | **505** | **0** | | |

Example command (S3):

```powershell
$env:MAVEN_OPTS="-Xmx1g"
mvn -T1 -f backend/pom.xml -pl dispatch-courier-service -am test "-Dtest=AssignmentCoordinationPostgresTest,AssignmentPostgresIntegrationTest,AssignmentToCourierScenarioTest,CourierWorkflowIntegrationTest,GatewayCourierOwnershipIntegrationTest,ProofPostgresIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

### Coverage relevant to Phase 5

| Concern | Verified by |
|---|---|
| Courier delivery endpoint, ownership and validation | S3 `CourierDeliveryControllerTest`, `SubmitProofRequestTest`, `ProofPostgresIntegrationTest`, `GatewayCourierOwnershipIntegrationTest` (real Keycloak tokens) |
| SERVICE token acquisition and failure states | S3 `ServiceTokenProviderTest`, `ServiceTokenProviderResilienceTest` |
| S3 → S2 POD gRPC client, retries and downstream failure | S3 `PodGrpcClientInterceptorTest`, `ParcelStatusClientTest`, `ParcelStatusClientResilienceTest`, `ProofCompletionWorkerTest` |
| gRPC caller identity and POD permission | S1 `GrpcSecurityInterceptorTest`, S2 `ParcelPricingGrpcSecurityTest`, S3 `DispatchGrpcSecurityInterceptorTest` |
| S2 status update with an atomic outbox write and publication to Kafka | S2 `ParcelStatusIntegrationTest`, `ParcelStatusOutboxAtomicityIntegrationTest`, `OutboxKafkaIntegrationTest`, `OutboxRestartRecoveryIntegrationTest` |
| S4 consumption and duplicate handling | S4 `TrackingKafkaConsumerIntegrationTest`, `TrackingDuplicateEventProcessingTest` |
| S5 consumption, retry, recovery and failure | S5 `NotificationKafkaIntegrationTest`, `NotificationRetryIntegrationTest`, `NotificationRecoveryIntegrationTest`, `NotificationFailureIntegrationTest` |
| Gateway issuer, audience, expiry, signature and path normalization | `GatewayKeycloakIntegrationTests` |

These suites verify each service's part of the POD workflow against real infrastructure. They do not verify a single request crossing all services. That is what `DistributedPodWorkflowIT` covers, and it was not run here.

## Frontend results

```powershell
cd frontend/zentro-web
npm test -- --minWorkers=1 --maxWorkers=2
```

Result: 72 test files and 844 tests passed in 268.6 s. Vitest 2.1 rejects `--maxWorkers` below its default minimum unless `--minWorkers` is also lowered.

MSW blocked two requests that had no handler; both tests still passed:

- `GET /api/v1/dispatch/workload` in `src/app/router.test.tsx` ("renders courier management for an authenticated dispatcher")
- `GET /api/v1/merchants/api-key` in `src/portals/merchant/pages/__tests__/MerchantPages.test.tsx` ("ApiKeysPage")

Adding handlers for these would remove the warnings.

## Not run

| Item | Reason |
|---|---|
| `DistributedPodWorkflowIT` and other `merchant-order-webhook-e2e` ITs | Opt-in (`-DrunE2E`) and need the packaged service jars. They start several service JVMs plus Postgres, Kafka and Keycloak containers, which needs more free memory than was available. See [Distributed POD E2E](distributed-pod-e2e.md). |
| Playwright E2E (`tests/e2e`, 8 specs) | No Playwright browsers were installed. The config starts `npm run dev` with the machine's local `.env`, and only 4 of the 8 specs mock their API calls. |
| Runtime Compose stack with provisioned service clients | Requires Keycloak and the four `ZENTRO_*_SERVICE_CLIENT_SECRET` values. Kafka is not defined in either Compose file, and the local service `.env` files pointed at shared databases. |

## Safety precautions for full-context tests

Each backend service's `application.yml` contains `spring.config.import: optional:file:./.env[.properties]`. Surefire runs tests from the module directory, so a developer's local `backend/<service>/.env` is loaded into every Spring test context. If that file points at a shared database, a `@SpringBootTest` that does not override the datasource could connect to it and run Flyway against it.

Before running a module's tests on a machine with real `.env` files, confirm that every full-context test overrides `spring.datasource.url`, `username` and `password`, or builds its own datasource from a Testcontainers container. All 42 container test classes in this run did, and so did the base classes they extend. Web-layer slices (`@WebMvcTest`, `@GraphQlTest`) do not create a datasource.

Also check outbound integrations before running:

- **S5:** DeepSeek is used only with an empty key or a loopback stub server. Merchant webhook delivery stays disabled unless a test enables it and points it at a loopback receiver. The only notification provider is `MockSmsSender`.
- **Gateway:** tests that override only some upstream URIs fall back to `.env` values. These were Compose DNS names, which do not resolve from the host.

## Repeating this on another laptop

1. Check free memory and running containers first. Do not stop other people's processes or containers.
2. Build with the reactor so the security starter is compiled from source. Running a service with `-f backend/<service>/pom.xml` alone uses whatever starter jar is in `~/.m2`, which may be stale (here it lacked `com.zentro.security.observability`):

   ```powershell
   $env:MAVEN_OPTS="-Xmx1g"
   mvn -T1 -f backend/pom.xml -pl <module> -am test
   ```

3. Run unit and slice tests first, excluding Testcontainers classes with `-Dtest=!ClassA,...` and `-Dsurefire.failIfNoSpecifiedTests=false`.
4. Run container tests one module at a time with `-Dtest=ClassA,ClassB,...`. Kafka and Keycloak runs reduced free RAM by about 1.2–1.8 GB at their peak.
5. Docker Desktop's WSL VM (`vmmemWSL`) can keep memory after containers exit and releases it gradually. Check free RAM between modules.
6. Ryuk removes Testcontainers containers after the JVM exits. S3 disables Ryuk in its Surefire configuration, so compare the container count before and after S3 runs.
7. Run frontend tests with `npm test -- --minWorkers=1 --maxWorkers=2` on low-memory machines.
