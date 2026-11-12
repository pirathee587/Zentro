# Issue #351 implementation and verification

Date: 2026-11-12 (Asia/Colombo). Authority: [Issue #351](https://github.com/pirathee587/Zentro/issues/351), complete body read through the GitHub connector. [PR #344](https://github.com/pirathee587/Zentro/pull/344) is merged as `f7f822e723bbd122cf2d22a302cb5aa5d498abed`; its contract tests are reused. Historical test counts are not current execution evidence.

## Git provenance and boundaries

Initial branch `feature/183-security-role-signoff`, clean, HEAD `a7ebce8ab40bebb75fa84e6667494c4f4f1ce12a`. Existing #163/#173/#183 branches and the #153 stash were preserved. Fetch succeeded after sandbox write approval; develop switched and fast-forwarded successfully to `9275ea8ccb3170097757e332525125264769d21f`, equal to origin/develop. New branch `feature/351-gateway-grpc-and-cross-service-contracts` started at that SHA, ahead/behind 0/0, clean. All implementation is on that branch. No push, PR creation, issue mutation or messages to other developers were performed.

Only production change: S4 `GlobalTrackingExceptionHandler.java`, explicitly owned by #351. Other changes are focused tests, evidence generators and architecture/testing documentation. No frontend, migration, build POM, workflow, deployment or domain business implementation changed.

## Work package results

| Package | Implemented / inspected | Remaining acceptance |
|---|---|---|
| G20 | Focused handling for MethodArgumentTypeMismatchException on /api/v1/tracking/mine page/size/limit. Fixed safe 400 ProblemDetail, INVALID_ARGUMENT, path-only instance; no submitted value/exception/cause logged by handler. Existing unrelated conversion handling retained. MockMvc tests cover malformed/overflow inputs, empty defaults, integer boundaries, 401/403 precedence, correlation. Existing public tracking unknown-reference 404 tests reused. PostgreSQL regression updated from known 500 to required 400 and extended. | PostgreSQL execution needs Docker; numeric clamp/max100 covered by existing skipped PostgreSQL test and source inspection, not by mocked HTTP tests. |
| G21 | Existing default-deny, roles, JWT, CORS, header/error/timeout suites reused. Added real gateway HTTP SSE bytes/content type, reconnect cursor, bearer/correlation propagation, identity sanitization and expired/SERVICE-only rejection with declared stub seams. Added executable replica-local quota and untrusted forwarded-IP characterization. | Live S4/auth-provider reconnect/expiry and replay evidence, workload budgets and multi-replica scope acceptance remain pending #351/#352/#357/#358/#359. No speculative routes or policies. |
| G14 | Canonical proto/event and REST seam inventory; generated-code golden-wire tests pin all three existing RPCs, field tags and additive unknown-field retention. Existing real gRPC server security/deadline/propagation suites reused. | #350 and provider owners must approve minimum eligibility/claim/release/hydration contracts and transport choices. No invented signatures/adapters. TLS/broker/live providers unverified. |
| G29 | Current five OpenAPI specs checked; 59 REST operations plus four GraphQL query fields, three declared RPCs and six named event schemas inventoried. Candidate test links and explicit per-operation branch gaps generated. Added genuinely missing pagination/SSE/wire assertions. PR #344 positive/negative/privacy suites reused. | Per-operation candidate links are not a complete branch audit. Live consumer/provider acceptance and approved new interfaces remain open. delivery.failed is envelope-only with no active provider found; owner decision required. |
| X03 | Command-line JaCoCo measurement attempted without POM/CI changes; package/layer counter generator and #352 instrumentation/enforcement handoff prepared. | Final measured results below; no signed-off coverage exception or CI gate claimed. Docker skips limit evidence. |
| X05 | Source-based decision register, existing SSE preserved; AI/MCP/Redis/distributed limiting/WebSocket and budgets have named decision owners. | Product/security/domain owners must explicitly approve/defer release scope. Recommendations are not approvals. |

## Test seams and limitations

Tracking MVC tests use the actual controller, MVC argument binding, advice and security chain, with mocked TrackingService/JwtDecoder. Integer boundary HTTP tests prove delegation to unchanged service logic, not real SQL clamping. Existing TrackingServiceTest and PostgreSQL pagination tests are distinct evidence. Handler-safe logging is tested directly so framework DEBUG diagnostics are not mistaken for application handler logging. The local run emits Spring DEBUG conversion diagnostics containing synthetic rejected input. Deployment logging configuration and framework diagnostics need S4/#352 review; this handler change does not claim to suppress all framework debug output. Existing `/v3/api-docs` missing-resource test still expects 500 under generic advice; no unrelated endpoint contract was changed. Unknown tracking references retain TRACKING_NOT_FOUND 404.

SSE tests use a finite MockWebServer stream and a stub ReactiveJwtDecoder; they prove gateway transport only. Existing S4 tests exercise owner/expired-token checks with mocked repositories. Last-Event-ID forwarding does not prove replay; current S4 controller has no cursor/replay input. Idle expiration relies on existing cleanup/per-event behavior; a response timeout is not a token lifetime. Real Keycloak/PostgreSQL/Kafka tests skip when Docker is absent. No shared or production databases were used.

Java 21.0.4, Maven 3.9.11, Python 3.13.15, Windows 11. Parent services use Boot 3.3.5; gateway has its own existing Boot version (3.5.16 observed). Docker CLI is present; daemon pipe dockerDesktopLinuxEngine is absent (`docker info --format '{{.ServerVersion}}'`, exit 1).

## Commands and execution evidence

Maven commands below run from `backend`; Python/git commands from repository root. PowerShell property arguments are quoted to preserve dots/commas. Raw logs are local ignored files under `docs/testing/issue-351-evidence/`; they can contain synthetic framework diagnostics and are not committed as release evidence. Committed summaries below record results without dumping request data.

| Command | Exit / evidence |
|---|---|
| `git status`; `git branch --show-current`; `git branch -a`; `git stash list`; `git remote -v`; `git log -1 --oneline` | 0; clean initial state, stash preserved |
| `git fetch origin --prune` | Initial sandbox exit 1 (FETCH_HEAD write denied); approved rerun 0 |
| `git switch develop`; `git pull --ff-only origin develop`; `git switch -c feature/351-gateway-grpc-and-cross-service-contracts` | 0 each; synchronized base above |
| `python docs/api/build_openapi.py --check` | 0; validity/controller mapping PASS, five specs |
| `mvn --batch-mode --no-transfer-progress -pl tracking-reporting-service -am '-Dtest=TrackingPaginationContractTest,CustomerTrackingControllerTest,PublicTrackingControllerTest,GlobalTrackingExceptionHandlerSafeLoggingTest,TrackingServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify` | Early unquoted property attempt exit 1 (PowerShell parsing); quoted sandbox attempt exit 1 (cache access); approved attempt exit 1 (new-test parenthesis compilation error, corrected). Subsequent focused runs exit 1, 37 tests/7 failures/0 errors/0 skips, due to asserting absence of framework DEBUG output. Corrected by isolating direct handler logging assertions while retaining HTTP safe-body checks. Logs tracking-focused.log and tracking-focused-rerun.log. |
| `mvn --batch-mode --no-transfer-progress -pl tracking-reporting-service -am verify` | 0; tracking 150 tests, 130 passed/0 failed/20 skipped; shared starter counts in subsequent complete run. Log tracking-verify.log. Subsequent correlation/PostgreSQL additions are verified by final reactor below. |
| `mvn --batch-mode --no-transfer-progress -pl api-gateway,identity-partner-service -am '-Dtest=Gateway*Tests,*FilterTest,CorsConfigTest,*ValidatorTest,*AuthorizationManagerTest,RoleConverterTest,ProblemDetailHandlersTest,ProtobufWireCompatibilityTest,GrpcSecurityInterceptorTest,IdentityPartnerGrpcPropagationTest,IdentityPartnerGrpcResilienceIntegrationTest,IdentityPartnerGrpcServerIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify` | 0; gateway 80 tests (66 passed, 14 skipped); selected S1 25 passed; 0 failures/errors. Log gateway-grpc.log. Later limiter characterization verified separately below. |
| `mvn --batch-mode --no-transfer-progress org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.12:report` | 1; S3 AssignmentPostgresIntegrationTest and AssignmentToCourierScenarioTest have Docker environment errors. Upstream modules passed. Log backend-coverage-verify.log. S2 had preexisting jacoco.exec, replaced by the non-appending rerun below; other initial exec files were newly created in this run. |
| `mvn --batch-mode --no-transfer-progress -pl tracking-reporting-service,notification-integration-service -am org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.12:report` | 0; final S4/S5 and starter results below. Log remaining-coverage-verify.log. |
| `mvn --batch-mode --no-transfer-progress -pl api-gateway,parcel-pricing-service -am '-Djacoco.append=false' org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent verify org.jacoco:jacoco-maven-plugin:0.8.12:report` | 0; final gateway/limiter and fresh S2 results below. Log fresh-gateway-parcel-coverage.log. |
| `mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am org.jacoco:jacoco-maven-plugin:0.8.12:report` | 0; report generation only, **not** a passing S3 verification. Log dispatch-partial-report.log. |
| `python docs/testing/build_issue351_inventory.py`; `python docs/testing/build_issue351_coverage.py` | 0; reproducible documentation generators; regenerated after final measurement |

## Final executed results

These are the latest complete per-module summaries, not a single green reactor run. Counts exclude prior repeated attempts. The default E2E harness was not reached after S3 stopped the reactor; real `-DrunE2E` was not attempted without Docker. Skipped scenarios are not passes. S3 error entries are failed container setup, not assertions or suppressed failures.

| Module | Tests reported | Passed | Assertion failures | Errors | Skipped | Final command/log |
|---|---:|---:|---:|---:|---:|---|
| shared starter | 62 | 62 | 0 | 0 | 0 | fresh-gateway-parcel-coverage.log |
| gateway | 81 | 67 | 0 | 0 | 14 | fresh-gateway-parcel-coverage.log |
| S1 | 434 | 411 | 0 | 0 | 23 | backend-coverage-verify.log |
| S2 | 674 | 587 | 0 | 0 | 87 | fresh-gateway-parcel-coverage.log |
| S3 | 258 | 235 | 0 | 2 | 21 | backend-coverage-verify.log |
| S4 | 151 | 130 | 0 | 0 | 21 | remaining-coverage-verify.log |
| S5 | 474 | 434 | 0 | 0 | 40 | remaining-coverage-verify.log |
| Total (latest per module) | 2134 | 1926 | 0 | 2 | 206 | Not a successful full-reactor acceptance run |

New assertions: TrackingPaginationContractTest 14 invocations; safe-logging regression 1; GatewaySseContractTests 2; replica-local limiter characterization 1; ProtobufWireCompatibilityTest 2. All 20 execute successfully. The new PostgreSQL malformed-pagination case is an additional **skipped** test, not part of those 20 passes. The existing PostgreSQL expected-500 assertion now expects 400 and is also skipped here. Live Keycloak tests, PostgreSQL/Kafka integration suites and E2E acceptance remain environment-dependent.

Fresh measured all-line coverage: starter 84.33%, gateway 98.77%, S1 50.92%, S2 76.92%, S3 64.98% (partial due environment errors), S4 78.53%, S5 75.86%. Service-layer coverage: S1 79.25%, S2 82.81%, S3 91.69%, S4 78.64%, S5 86.21%. Security-package coverage: starter 84.33%, gateway 100%, S1 96%, S3 100%, S4 91.80%, S5 96.12%; S2 has no separately measured security package and consumes the shared starter. These package selections are explicit in the generator; security-related config and reporting package membership still need #352 gate decisions. No all-service coverage enforcement or signed-off exception is claimed.

The S2 rerun matters: its historical-appended initial total was 91.20%; fresh actual coverage is 76.92%. Only the fresh result is accepted in the matrix. Reports are local at `backend/<module>/target/site/jacoco/index.html` and `jacoco.xml`; all package counters and missing-test owner recommendations are in the coverage matrix.

## Documentation / acceptance handoff

Verified implementation candidate: `673d392` (same production/test contents as the successful final module runs). Local commits: `51f02bc` tracking fix; `4d82b70` gateway characterization; `673d392` protobuf compatibility. The documentation commit follows this candidate; obtain its full final SHA with `git rev-parse HEAD`. No post-test production/test edits were made. Final `git diff --check` and staged checks passed; OpenAPI consistency and evidence generators were rerun successfully.

Changed files (complete owned scope):

```text
backend/api-gateway/src/test/java/com/zentro/gateway/filters/RateLimitFilterTest.java
backend/api-gateway/src/test/java/com/zentro/gateway/routes/GatewaySseContractTests.java
backend/identity-partner-service/src/test/java/com/zentro/identitypartner/grpc/ProtobufWireCompatibilityTest.java
backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/exception/GlobalTrackingExceptionHandler.java
backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/exception/GlobalTrackingExceptionHandlerSafeLoggingTest.java
backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/integration/TrackingPaginationIntegrationTest.java
backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/tracking/controller/TrackingPaginationContractTest.java
docs/architecture/cross-service-contract-inventory.md
docs/architecture/gateway-routes.md
docs/architecture/optional-technology-decisions.md
docs/testing/backend-contract-matrix.md
docs/testing/backend-coverage-matrix.md
docs/testing/build_issue351_coverage.py
docs/testing/build_issue351_inventory.py
docs/testing/issue-351-implementation.md
```

- [Current operation matrix](backend-contract-matrix.md): documented response statuses, roles/ownership source and candidate suites; explicit gaps rather than invented coverage.
- [Coverage/package matrix](backend-coverage-matrix.md): measured counters, report paths, layer selection and #352 owner handoff.
- [Cross-service inventory](../architecture/cross-service-contract-inventory.md): proto/REST/event fields, transport/auth/errors, compatibility and approval gaps.
- [Gateway evidence](../architecture/gateway-routes.md): current section supersedes historical CORS and count claims.
- [Optional decisions](../architecture/optional-technology-decisions.md): evidence, recommendation, owners and pending scope decisions.

Issue #351 is **PARTIALLY COMPLETE**, irrespective of local green checks. Final acceptance requires disposable Docker-backed verification, agreed provider/privacy/eligibility/claim/hydration contracts, approved budgets/optional-scope decisions, complete operation assertion review, coverage enforcement or approved exceptions via #352, then independent review by kirusigan03 and merge into develop. The user owns pushing, PR creation and issue closure.

This branch is ready for a **focused partial-scope PR** covering the safe pagination fix and verified characterization, with the skips/environment errors disclosed. It is not ready to claim final Issue #351 acceptance. Next concrete runtime action: make a disposable Docker engine available, run `mvn --batch-mode --no-transfer-progress -Dbuild.revision=<final-head> -DrunE2E verify` from backend and the focused PostgreSQL pagination suite; retain any real failures for the owning package. In parallel, the named owners in the contract/decision registers must record the unresolved contracts, budgets and scope decisions. No approval is presumed.

## Docker-enabled follow-up verification (2026-11-12)

This section supersedes the earlier Docker-unavailable status for the new executions only; the earlier results remain historical evidence. Started on the expected branch at `31a0108486456a59f4e13a87e1d357e3979f35ba`, with a clean working tree. No branch switch or source change was made.

Docker CLI 29.9.2 initially received a sandbox named-pipe permission error. Approved execution outside the sandbox confirmed Docker Desktop 4.97.0 / Engine 29.9.2, API 1.56 (minimum 1.40), Linux engine running. Testcontainers 1.20.4 successfully discovered the engine and started disposable PostgreSQL 16.15 containers. Existing S3 configuration selects API 1.44; no build/configuration change was needed.

The two exact previous errors were inspected in their Surefire text reports: both failed before test execution with `IllegalStateException: Previous attempts to find a Docker environment failed. Will not retry.` The retry selected only those two fully qualified classes, with `-am` for required reactor dependencies and `surefire.failIfNoSpecifiedTests=false` for dependency modules that have no selected tests.

| New command (Maven from backend) | Exit | Tests / result |
|---|---:|---|
| `docker version`; `docker info --format '{{.ServerVersion}}'`; `docker ps --format '{{.Names}} {{.Status}}'` | 0 each outside sandbox | Engine reachable; existing zentro-keycloak running |
| `mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=com.zentro.dispatchcourier.dispatch.repository.AssignmentPostgresIntegrationTest,com.zentro.dispatchcourier.integration.AssignmentToCourierScenarioTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify` | 0 | AssignmentPostgresIntegrationTest: 17 passed; AssignmentToCourierScenarioTest: 12 passed; total 29, zero failures/errors/skips. Local log docker-retry-failed-tests.log. |

Existing Keycloak protection: recorded container ID `9840a6560a1ca51aa44ad24c02176ba328badc5a2ec918bd8c68beea679e8df0`, running, StartedAt `2026-11-12T16:06:36.256177853Z`. No stop/remove/update command targets it. `DisposableGatewayStack` creates independent PostgreSQL/Kafka/Keycloak containers on mapped ports and terminates only its own fixture processes/containers. No Docker volume, image or existing database deletion command was issued.

### Broader verification result

After the exact-class retry passed, ran from `backend`:

```powershell
mvn --batch-mode --no-transfer-progress --fail-at-end '-Dbuild.revision=31a0108486456a59f4e13a87e1d357e3979f35ba' -DrunE2E verify
```

**Exit 0, BUILD SUCCESS**, completed 2026-11-12 22:04:27 Asia/Colombo in 20:01 minutes. Local ignored log: `docs/testing/issue-351-evidence/docker-reactor-e2e-verify.log`. All eight test modules succeeded. This is one complete successful reactor/E2E run, superseding the earlier per-module aggregate for runtime verification. The focused 29-test retry is repeated within this run and is **not** added again to the total.

| Module | Passed | Assertion failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Shared security starter | 62 | 0 | 0 | 0 |
| API gateway | 213 | 0 | 0 | 0 |
| S1 Identity & Partner | 434 | 0 | 0 | 0 |
| S2 Parcel & Pricing | 688 | 0 | 0 | 0 |
| S3 Dispatch & Courier | 285 | 0 | 0 | 0 |
| S4 Tracking & Reporting | 151 | 0 | 0 | 0 |
| S5 Notification & Integration | 489 | 0 | 0 | 0 |
| Merchant order/webhook E2E | 2 | 0 | 0 | 0 |
| **Total** | **2324** | **0** | **0** | **0** |

Counts come from the eight final Maven module summaries, cross-checked against the current focused Surefire reports. Previously skipped parameterized/dynamic suites expand when their fixtures can start, so the new total is not obtained by merely adding 206 historical skips to 1,926 passes. GatewayKeycloakIntegrationTests now executes 146 signed-token cases successfully. TrackingPaginationIntegrationTest now executes all four PostgreSQL cases successfully, including malformed page/size/limit and overflow safe-400/correlation assertions, existing ownership isolation, numeric clamping and maximum size 100. The 20 previously added non-container regression cases also pass in the full run.

MerchantApprovalGapIT passes both existing E2E cases using its independent Keycloak/PostgreSQL/Kafka fixture and real gateway/service processes. Its manual role assignment is a declared test-fixture step, not evidence that automated provisioning has been approved or implemented. The E2E profile excludes PerformanceSmokeIT; no new performance/load or full live-SSE replay/expiry claim is made. Existing test doubles in S3 eligibility/provider scenarios remain as documented in those suites. Coverage instrumentation/enforcement was not changed, and this follow-up does not publish refreshed coverage percentages; the preceding coverage matrix remains evidence from its explicitly recorded earlier measurement.

Additional verification: `python docs/api/build_openapi.py --check` returned **0**, five specifications passed. Final `docker inspect --format '{{.Id}} {{.State.Status}} {{.State.StartedAt}}' zentro-keycloak` returned **0** and exactly the original container ID/start time above, still running. `docker ps --format '{{.Names}} {{.Status}}'` returned **0** and showed only the pre-existing `zentro-keycloak` after disposable fixture cleanup. No existing container was stopped, removed or restarted.

### Diagnosis and readiness

The two previous errors are confirmed **environment failures resolved by Docker availability and approved sandbox access**. No confirmed application defect remains in the executed suites, and no production or test source change was necessary. Only `docs/testing/issue-351-implementation.md` changes in this follow-up; raw test/process logs remain ignored local evidence. The tested implementation SHA is `31a0108486456a59f4e13a87e1d357e3979f35ba`; the subsequent local commit records documentation only. No push, PR creation or issue closure is performed.

The branch is **ready for a Draft PR** with this verification evidence. Issue #351 remains partially complete: provider/privacy/eligibility/claim/hydration contracts need the named #350/#353/#354/#357 owners and #351 agreement; operation-budget and optional-technology decisions need product/security and #351/#352; coverage gates remain #352 (Niro0526); a complete per-operation assertion audit and live SSE semantics remain explicitly scoped gaps. Independent review by kirusigan03 and merge are still required. These are acceptance/decision gaps, not current Docker blockers, and the green run does not constitute approval for those interfaces or policies.
