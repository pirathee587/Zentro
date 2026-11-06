# ZD-34 courier workflow testing — Issue #69

Date: 2026-11-06 (Asia/Colombo).
Branch: `feature/69-courier-workflow-testing`.
Baseline: `e11da257c6c28ad52d8eceb15e26f1a423991302` (PR #274).
Status: AUTOMATED S3 SCOPE VERIFIED / LIVE CROSS-SERVICE E2E UNVERIFIED.
No commit or PR created. Latest verification: 2026-11-06, after Docker became available.

## Scope and test boundaries

WARP.md and CONTRIBUTING.md were read before changes. Only tests and this report
were changed; production code, Issue #121 implementation, dependencies and Flyway
migrations remain unchanged. Existing `com.zentro` packages and test conventions
are preserved.

The new CourierWorkflowIntegrationTest runs a real random-port S3 HTTP server,
security filter chain, application services, repositories, Flyway and disposable
PostgreSQL 16. **IdentityCourierClient (S1), ParcelSource, JwtDecoder and
ParcelStatusClient (S2) are Mockito doubles.** Bearer requests exercise role
conversion/authorization but do not verify cryptographic JWT validation; existing
ZeroTrustJwtSecurityTest covers that separately. This is S3 integration coverage,
not a Keycloak/gateway/cross-service E2E test.

Persisted completion cases use the real proxied ProofRepository and actual
ProofCompletionWorker, driven explicitly with scheduling disabled. S2 success,
transition rejection and transient failure are simulated at ParcelStatusClient.
No assertion proves that a real S2 parcel changed status or published an event.

## Files and added cases

Paths relative to repository root:

| File | Added coverage |
|---|---|
| `backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/courier/controller/CourierDeliveryControllerTest.java` | 8 cases: four wrong-role list/detail denials, anonymous list/detail denial, independent profile/user mismatch detail denials, matching-profile/wrong-user proof GET/POST denial |
| `backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/dispatch/repository/AssignmentPostgresIntegrationTest.java` | 2 parameterized cases: observe an actual PostgreSQL advisory-lock waiter, verify insert remains blocked, then release via holder commit or rollback and verify transaction lock release |
| `backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/integration/CourierWorkflowIntegrationTest.java` | 8 integration cases, listed below |
| `frontend/zentro-web/src/portals/courier/pages/__tests__/DeliveryPages.test.tsx` | 7 cases: delayed A response after B login, logout evidence removal/sign-in, same-route account change draft removal, PENDING polling to three terminal states, denial on proof refresh hides old evidence |
| `docs/test-reports/zd-34-courier-workflow-test-report.md` | This evidence and blocker record |

New integration cases:

1. Two assignments created through dispatcher HTTP; A/B lists isolate parcels;
   B cannot read A detail/proof or submit A proof, and denial writes nothing.
2. HTTP submission/replay/conflict persists canonical parcel/actor; conflicting
   evidence cannot overwrite accepted proof and parcel B remains untouched.
3. Concurrent identical HTTP requests return one 201 and one 200 with one proof row.
4. Anonymous, customer and invalid-evidence requests leave no proof.
5. Fresh mocked S1 ineligibility rejects a new assignment; duplicate assignment
   conflict preserves original ownership and row count.
6. Worker acknowledges persisted proof A only, never calls completion for B.
7. Rejected completion retains evidence, has no completion timestamp and rejects replacement proof.
8. New worker instance retries persisted transient failure and completes the same
   proof with unchanged submission time and two durable attempts.

Total: 25 added parameterized test invocations. Existing same-parcel uniqueness,
proof repository concurrency and basic form tests were retained without duplication.

## Environment

- Windows 11, PowerShell.
- Java 21.0.4, Maven 3.9.11, Node 22.19.0.
- Existing npm/Maven dependencies reused; no dependencies installed.
- Docker Desktop Linux engine 29.9.2 available. Sandbox pipe access is denied;
  approved execution outside the sandbox successfully ran Testcontainers.
- Tests started disposable PostgreSQL 16 containers and an isolated random-port S3
  server. No shared application databases or live Keycloak/services were modified.
- PostgreSQL integration runtime executed successfully; no database tests skipped.

## Latest verification commands and actual results

All previous Issue #69 changes were present at startup on the same branch/baseline.
Commands below used existing dependencies. Maven and Vite commands needing Docker
pipe/process access ran outside the sandbox with approval.

| Command | Actual result |
|---|---|
| `docker info --format '{{.ServerVersion}}'` | 29.9.2; sandbox attempt denied, approved retry succeeded |
| `mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am test` (backend directory, first Docker run) | Security starter: 7 passed. S3: 143 passed, 0 assertion failures, 1 test-client error, 0 skipped |
| `mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am -Dtest=CourierWorkflowIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test` | After test-only correction: 8 passed, 0 failures/errors/skips; BUILD SUCCESS |
| `mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am test` (final regression) | BUILD SUCCESS. Security starter: 7 passed; S3: 144 passed; combined: 151 passed, 0 failures, 0 errors, 0 skipped |
| `npm test -- src/portals/courier/pages/__tests__/DeliveryPages.test.tsx src/app/router.test.tsx src/shared/auth/AuthProvider.test.tsx` | 38 passed, 0 failures/skips; courier 25, router 6, auth 7 |
| `npm run typecheck` | Passed |
| `npm run lint` | Passed; 0 errors, 6 pre-existing warnings |
| `npm run build` | Passed; Vite chunk-size warning |
| `git diff --check` | Passed |

Final backend suite breakdown (no failures/errors/skips):

| S3 suite | Passed |
|---|---:|
| CourierDeliveryControllerTest | 26 |
| SubmitProofRequestTest | 9 |
| ProofPostgresIntegrationTest | 9 |
| IdentityCourierClientTest | 11 |
| ParcelStatusClientTest | 15 |
| ProofCompletionWorkerTest | 4 |
| ServiceTokenProviderTest | 9 |
| DispatchQueueControllerTest | 16 |
| AssignmentPostgresIntegrationTest | 5 |
| DispatchServiceTest | 15 |
| ExceptionHandlersTest | 2 |
| CourierWorkflowIntegrationTest | 8 |
| ZeroTrustJwtSecurityTest | 15 |
| Total | 144 |

The five assignment tests include both new advisory-lock commit/rollback cases.
All 25 Issue #69 additions executed successfully across backend and frontend.
Counts above do not sum repeated executions. Full S1/S2/S4/S5/gateway suites and
the entire frontend suite were not run in this focused verification.

The first Docker run exposed a **test transport mistake**: TestRestTemplate's
HttpURLConnection streaming request factory threw HttpRetryException on the
unauthenticated POST rather than exposing HTTP 401. Only the new integration
test setup was corrected to use the existing JdkClientHttpRequestFactory. The
same 401 expectation then passed. No production code or expectations changed.

Logs are ignored: `backend/dispatch-courier-service/target/zd34-docker-final.log`,
`target/zd34-workflow-rerun.log` in that same service, and frontend
`zd34-*-verification.log`. Surefire XML contains per-case backend results.

## Historical results before Docker was available

Backend commands run from `backend/` (all use
`mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am`
and `-Dsurefire.failIfNoSpecifiedTests=false`):

| Test selection (`-Dtest=...`) | Result |
|---|---|
| `CourierDeliveryControllerTest` | 26 passed, 0 failures/errors/skips |
| `AssignmentPostgresIntegrationTest` (first attempt) | Test-code compilation error: multiline SQL string; corrected in test code |
| `AssignmentPostgresIntegrationTest,CourierWorkflowIntegrationTest` (before completion additions) | Compilation succeeded; assignment class setup Docker error, 4 integration tests skipped |
| `CourierWorkflowIntegrationTest,CourierDeliveryControllerTest` (7 integration cases at that point) | Maven BUILD SUCCESS: 26 passed, 7 skipped, 0 failures/errors; database tests NOT verified |
| `CourierDeliveryControllerTest,CourierWorkflowIntegrationTest,AssignmentPostgresIntegrationTest` (final sources) | Maven BUILD FAILURE from Docker setup: 26 passed, 0 assertion failures, 1 class-level infrastructure error, 8 skipped. The 2 new lock invocations and 3 existing assignment tests could not execute. |

Historical Surefire aggregate: 35 reported entries, 0 failures, 1 error, 8 skipped.
The class-level initialization error is not a failed production assertion and is
not a test pass. Do not add totals across reruns.

Frontend commands run from `frontend/zentro-web/`:

| Command | Result |
|---|---|
| `npm test -- src/portals/courier/pages/__tests__/DeliveryPages.test.tsx` | Initial sandbox attempt blocked by esbuild `spawn EPERM`; rerun with approved process access: 22 passed, 3 test synchronization failures |
| `npm test -- src/portals/courier/pages/__tests__/DeliveryPages.test.tsx src/app/router.test.tsx src/shared/auth/AuthProvider.test.tsx` | After correcting loading-state waits: 38 passed, 0 failed/skipped, 3 files passed. Courier suite: 25; router: 6; AuthProvider: 7. |
| `npm run typecheck` | Passed |
| `npm run lint` | Passed: 0 errors, 6 existing warnings in unchanged production files |
| `npm run build` | Passed; existing Vite chunk-size warning |

At that earlier execution, `docker info --format '{{.ServerVersion}}'` confirmed the engine was unavailable.
`git diff --check` passed. Java/Node/Maven version commands confirmed versions above.
Generated logs/build outputs remain ignored; no fixture or runtime credentials
were written into tracked files. Full reactor and full frontend suite were not run.

## Defects, limitations and blockers

No new production defect was demonstrated by an executed test. Initial polling
failures, the SQL string compilation error and the HTTP test-client error were
mistakes in the new tests and were fixed without changing production behavior or
existing expectations. Latest executions have no remaining test failures.

The following previously identified implementation gaps remain:

| Item | Owner and next action |
|---|---|
| Stale availability between S1 eligibility response and S3 insert | #121 owner @Niro0526, coordinating S1 @pavi3alnx and S3 @Nilakshana23: define/implement boundary-safe coordination. PR #274 locks only the parcel insert; the prior Identity lookup is outside that lock. |
| Production assignment blocked by UnavailableParcelSource | S3 @Nilakshana23 with S2 @14Jannan: supply approved parcel eligibility integration. Test-only source does not resolve this. |
| Courier accept/reject and intermediate-status operations absent | #69 owner @pavi3alnx with S2/S3 owners: clarify acceptance scope or link prerequisite implementation; do not add features under this test change. |
| Docker execution | Resolved: all PostgreSQL suites executed successfully with approved Docker pipe access. |
| Real S1/S2/Keycloak/gateway E2E not run | Service owners / #69 owner: provision isolated runtime, separate databases, test identities and confidential SERVICE credentials. No Playwright dependency was added. |

Database setup uses only existing migrations. No distributed availability safety,
real S2 transition validation, event publication or complete courier E2E acceptance
is claimed by these doubles. Frontend tests are Vitest/RTL/MSW component integration.

## Before PR

1. Focused test-only change is ready for independent PR review, subject to user
   approval for Git operations. All requested automated checks passed.
2. Verify real S1/S2 contracts and outstanding workflow criteria in a separate
   isolated runtime when upstream integration is ready.
3. Attach final SHA, environment/results and independent review by @Jathuja.
4. Keep #69 partially verified until blocked acceptance evidence exists.
5. Obtain user approval before commit/push/PR; none was performed here.
