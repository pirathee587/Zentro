# ZD-092 dispatcher assignment-to-courier verification (#167)

Status: STEP 1 and STEP 2 automated checks and fixture-based browser visual review passed.
The integrated manual journey remains unverified (F2/F4/F6). The user authorized branch
push and PR creation; independent review and the human merge/merged SHA remain pending.

## Scope and decisions

Base: `f3e71ac9c60f57205f6ed98da8ef893f4b32a0ec` (`develop`).
Branch: `chore/167-dispatcher-assignment-to-courier-e2e`.
Primary owner: @14Jannan. Independent reviewer: @hephzibah-peries.
Proposed additional reviewers: @pirathee587 and @pavi3alnx; inform @Nilakshana23.

- D1=A: existing Playwright configuration, page objects and authentication patterns;
  stateful browser API responses plus isolated S3 HTTP/PostgreSQL scenarios.
- D2=A: assignment journey and lifecycle conflicts only. Full dispatcher regression
  stays in #254; cross-service backend integration stays in #226.
- D3=A: reassignment has no frontend flow; cover its API now and record #244 follow-up.
- D4=A: use the `chore/167-...` branch permitted by CONTRIBUTING and CI.
- D5: findings only; no production fix, permission-policy change or provisioning change.
- D6=A: maintain this report with reproducible commands and actual evidence.

Reassignment and unassignment require DISPATCHER only, matching the approved permission
matrix. A missing DISPATCH_REASSIGN claim is not a denial scenario for either endpoint.
Read/assign permission checks retain their existing behavior. No contracts, dependencies,
production code or migrations are changed. Only S3's disposable database is inspected.

## Dependencies and coordination

| Dependency | Confirmed evidence | Owner / next action |
|---|---|---|
| #131 assignment lifecycle | Merged PR [#333](https://github.com/pirathee587/Zentro/pull/333) | @Niro0526; reuse reassignment/cancellation and atomic audit/outbox |
| #133 dispatcher permissions | Merged PR [#304](https://github.com/pirathee587/Zentro/pull/304) | @pavi3alnx and security owners; reconcile policy/provisioning finding F1 |
| #155 dispatcher integration | Merged PR [#320](https://github.com/pirathee587/Zentro/pull/320) | @kirusigan03; reuse existing queue, workload and dialog |
| Existing Playwright setup | Merged PR [#323](https://github.com/pirathee587/Zentro/pull/323) | Reuse configuration, auth and page-object layout |
| #244 remaining assignment/history UI | Open | @Nilakshana23; reassignment UI remains outside this change |
| #226 cross-service assignment integration | Open | @Nilakshana23; production parcel-source integration evidence remains separate |
| #254 full dispatcher regression | Open | @Nilakshana23; reuse focused journey without expanding it to all screens |

The approved planning comment was posted to [#167](https://github.com/pirathee587/Zentro/issues/167#issuecomment-6800620162).
The policy comment for #133 is drafted only; no posting approval has been given for it.

## Scenario matrix and existing coverage

| ID | Scenario / expected result | Existing coverage reused | Added coverage |
|---|---|---|---|
| a | Empty queue; several parcels; refresh after assignment | DispatchQueuePage component tests: empty/incomplete states, rows and success refresh; DispatchServiceTest.excludesAuthoritativeAssignmentsAndAppliesSearchAndUtcDates | Browser empty/refresh test and assignment journey |
| b | Assignment counts and available/inactive/disabled couriers shown accurately | DispatchServiceTest.workloadUsesAssignmentCountsAndIdentityAvailability; AssignmentPostgresIntegrationTest.lifetimeWorkloadMatchesIndependentRecountAcrossProofStatesAndInactiveCouriers; WorkloadPage component tests | Browser reviews all four roster states and counts before/after assignment |
| c | Eligible assignment removes queue row and increments only its courier's count | DispatchServiceTest.assignsOnlyInsideSourceGuardAfterFreshCourierRevalidation; DispatchQueueControllerTest.validAssignmentUsesTokenSubjectAndCanonicalCourier | Browser journey with one shared response state |
| d | Courier becomes inactive/disabled after selection: 409 COURIER_INELIGIBLE; assignment data unchanged | DispatchServiceTest.courierFailureNeverPersists; CourierWorkflowIntegrationTest.freshIneligibilityAndAssignmentConflictsDoNotChangeStoredOwnership; dialog component test | Two browser variants plus two S3 replacement-ineligibility cases |
| e | Concurrent assignment has one winner and one explicit conflict | AssignmentPostgresIntegrationTest.competingServicesCommitExactlyOneAssignmentAndExcludeItFromQueue | Two isolated dispatcher browser contexts, coordinated submissions, one 201/one 409, refreshed loser and one workload increment |
| f | Assigned courier sees list/detail; another has no row and is denied detail | CourierWorkflowIntegrationTest.assignedVisibilityAndCrossCourierDenialUsePersistedAssignments; GatewayCourierOwnershipIntegrationTest.gatewayPreservesOwnershipAndDeniedOperationsLeaveS3DatabaseUnchanged | Two courier browser contexts and API 403/visible denial; S3 ownership checks after reassignment |
| g | Reassign moves visibility/count from old to new courier; audit and delivery.reassigned persisted | DispatchServiceTest.reassignRequiresFreshEligibleCourierAndUsesDispatcherSubject; AssignmentPostgresIntegrationTest.reassignmentAndCancellationWriteAuditedEventsAtomically | S3 role-only HTTP reassignment checks both couriers, both workloads and full audit/outbox attribution |
| h | Same courier: 409 COURIER_ALREADY_ASSIGNED; unassigned: 404 DELIVERY_NOT_ASSIGNED; valid role-only mutations succeed | DispatchServiceTest.reassignRejectsMissingAssignmentBeforeCheckingReplacementCourier; repository lifecycle/missing-cancellation tests | S3 conflict snapshots; role-only reassignment and unassignment success; guard 409/503 preserves state |
| h/i | CUSTOMER, MERCHANT, COURIER: 403; no token: 401 for both lifecycle mutations, without dependency calls or writes | DispatchQueueControllerTest.otherRolesCannotAccessQueue (also checks lifecycle mutations); anonymousCannotReassignOrCancel; ZeroTrustJwtSecurityTest | Four S3 caller variants check both mutations and all four S3 tables |
| i | Dispatch role guards and visible read/assign denials | Existing controller and signed-token permission tests | Three browser role-denial cases, anonymous sign-in boundary, and assignment 401/403 feedback without state changes |

The old repository test named `reassignmentIsRejectedWithoutChangingAttributionOrProof`
invokes `assign`, so it is evidence for duplicate-assignment rejection, not the reassign endpoint.
Actual picked-up/completed reassignment outcomes remain unverified: S3 delegates lifecycle
eligibility to ParcelSource, with no approved production adapter or documented status-specific
outcome in the inspected contract. The guard-failure tests assert delegation and preservation,
not a fabricated rule for those statuses.

## Test boundaries

Browser tests use page.route with state local to each test. Queue, workload and courier
list/detail responses share one assignment map across isolated contexts. Tests assert
observable UI messages, selections, counts, list/detail visibility and response statuses.
The browser race is response-model evidence; real database uniqueness is covered by the
existing PostgreSQL concurrency test. Mocked browser denials do not establish backend security.

AssignmentToCourierScenarioTest runs the real S3 HTTP filter chain, method security, services,
repositories, Flyway and PostgreSQL (`postgres:16-alpine`). JwtDecoder is mocked; tokens carry
realm_access.roles, with read permissions only where required. Lifecycle success deliberately
uses a DISPATCHER token with no permissions claim. The real IdentityCourierClient calls the
existing StubHttpServer. A mocked ParcelSource delegates to a second StubHttpServer on an
explicit test-only `/fixture/parcels/{id}` path. This is not an S2 endpoint or production adapter.
The worker and outbox publisher are disabled; assertions establish persisted envelopes,
not Kafka publication or consumption. No gateway, live Keycloak or other service database
participates in the added backend scenarios.

All failure snapshots compare assignment, proof, audit and outbox tables. Role denials also
verify no S1 or parcel-port requests. Successful lifecycle checks compare old/new courier IDs,
actor subject, parcel ID, event ID/type/action and record counts.

## How to run

Use Git Bash, Java 21, Maven 3.9+, Node/npm and Docker with local disposable databases.
Install the committed dependencies with `npm ci` if needed; install the matching Chromium
with `npx playwright install chromium`. These commands do not require a shared database.

```bash
cd /D/Sem_5/EAD/Project/Zentro/frontend/zentro-web
npm run lint
npm run type-check
npm run test
npm run build
# The existing customer specs expect the customer mock adapter. Override local real-mode settings.
VITE_USE_CUSTOMER_MOCKS=true VITE_CUSTOMER_API_MODE=mock npm run test:e2e

# Focused browser suite and existing HTML report viewer
npm run test:e2e -- tests/e2e/dispatcher
npx playwright show-report

# E2E files are outside the application's TypeScript project.
npx tsc --noEmit --strict --skipLibCheck --target ES2022 --module ESNext --moduleResolution Bundler tests/e2e/dispatcher/assignment-to-courier.spec.ts tests/e2e/dispatcher/assignment-conflicts.spec.ts

cd /D/Sem_5/EAD/Project/Zentro/backend
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am -Dtest=AssignmentToCourierScenarioTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am test

# Full reactor belongs to the post-commit verification checkpoint.
mvn --batch-mode --no-transfer-progress test package
```

## Actual verification results

Environment: Windows, Java 21.0.12.1, Maven 3.9.16, Node 24.13.0, npm 11.6.2,
Playwright 1.65.0 / Chromium revision 1254, Docker Desktop engine 29.8.0.
Execution date: 2026-11-11, Asia/Colombo.

| Check | Actual result |
|---|---|
| New fixture/page objects/specs ESLint and strict standalone TypeScript | Passed |
| Focused dispatcher browser suite | 11 passed (33.0s), no skips |
| Focused S3 scenario test | 12 run, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS (02:15 min) |
| Full frontend lint | Passed: 0 errors, 8 existing warnings |
| Frontend application type-check | Passed |
| Full frontend unit/component suite | 63 files, 729 tests passed (201.14s) |
| Production frontend build | Passed; Vite built in 34.15s; existing >500 kB chunk warning |
| Entire Playwright suite | Mock-mode command: 31 passed (2.6m), no skips; default local environment previously had 25 passed and 6 customer failures (5.1m) |
| Full S3 module tests | Rerun BUILD SUCCESS (04:45 min): S3 278 run, 0 failures, 0 errors, 0 skipped; security starter 62 passed |
| Full backend reactor test/package | STEP 2 BUILD SUCCESS: all 9 modules; 2,236 executed tests, 0 failures, 0 errors, 0 skips; 22:33 min |
| Manual integrated journey | Unverified; unavailable local application services and F2/F4/F6; fixture visual walkthrough recorded separately |

Earlier attempts are not passing evidence: STEP 0 ran on the old checkout (667 frontend
tests passed; no E2E script; S3 failed with Docker unavailable). After PREPARE, the first
existing browser run could not launch because revision 1254 was absent. Installing the
matching browser resolved launch. The first new journey run matched the document navigation
instead of the API response; its predicate was corrected to the `/api/v1/` path and rerun.
The full-suite local run used `.env.local` with `VITE_USE_CUSTOMER_MOCKS=false` and showed
"Unable to calculate fee" in six existing booking/quotation/validation cases. The rerun
sets the existing customer mock-mode switches for the command only; no local environment
file, existing spec or production code is modified. The TypeScript build emitted an
untracked `vitest.config.js` duplicate; it was removed after verification, not committed.
The first complete S3 run failed solely during GatewayCourierOwnershipIntegrationTest's
Keycloak startup: its existing 120-second readiness check expired while Keycloak was still
initializing its schema. All 12 new scenarios passed in that run. The unchanged suite is
rerun without concurrent frontend workloads passed, including the gateway ownership test;
no timeout, assertion or skip setting was changed. That earlier S3-only run used the available
packaged gateway artifact; STEP 2 below rebuilt it from the current base.

### STEP 2 post-commit full check

Verified after commit `67db07b`, on 2026-11-11 (Asia/Colombo). No runtime source changed.
The final verification evidence is included in the single task commit before push.

| Frontend command | Actual result |
|---|---|
| npm run lint | 0 errors, 8 existing warnings |
| npm run type-check | Passed |
| npm run test | 63 files, 729 tests passed; 120.16s |
| npm run build | Passed; Vite built in 11.14s; existing chunk-size warning |
| VITE_USE_CUSTOMER_MOCKS=true VITE_CUSTOMER_API_MODE=mock npm run test:e2e | 31 passed, no skips; 1.5m |

Exact backend command, from `backend/` on capital-D drive:

```bash
mvn --batch-mode --no-transfer-progress test package
```

| Backend module | Tests run | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| zentro-security-starter | 62 | 0 | 0 | 0 |
| api-gateway | 210 | 0 | 0 | 0 |
| identity-partner-service | 420 | 0 | 0 | 0 |
| parcel-pricing-service | 675 | 0 | 0 | 0 |
| dispatch-courier-service | 278 | 0 | 0 | 0 |
| tracking-reporting-service | 135 | 0 | 0 | 0 |
| notification-integration-service | 456 | 0 | 0 | 0 |
| Total executed | 2,236 | 0 | 0 | 0 |

Reactor summary: all 9 modules SUCCESS; BUILD SUCCESS; total time 22:33 min.
The test-only merchant-order-webhook-e2e module compiled and packaged, but its existing
default configuration excludes its opt-in E2E/performance suites. Neither profile was
enabled, and no skip setting was added or changed. This is not evidence for those suites.
The S3 gateway ownership test passed against the gateway rebuilt by this reactor.

Raw command output is retained locally in `%TEMP%/zd092-step2-frontend.log` and
`%TEMP%/zd092-step2-backend.log`; only summaries belong in the report/PR.

### Delegated browser visual review

The user delegated the local checks and authorized push/PR creation. A separate temporary
browser walkthrough reused the approved fixtures and page objects: 1 passed (14.1s).
All 10 captured screenshots were visually inspected. This is fixture-based browser evidence,
not a successful unmocked manual run or independent reviewer sign-off.

| View | Observed result | Local screenshot |
|---|---|---|
| Initial queue | Three unassigned rows and corresponding Assign controls | 01-queue.png |
| Assignment dialog | Eligible courier selection and enabled confirmation | 02-assignment-dialog.png |
| Successful assignment | Success notice, assigned row removed, count reduced to two | 03-assigned-queue.png |
| Workload | Courier One count 1; Courier Two count 0; inactive/disabled shown unavailable | 04-workload.png |
| Assigned courier | Delivery visible on the courier dashboard | 05-courier-owner.png |
| Other courier | No assigned row; direct detail shows Delivery not available | 06-courier-denied.png |
| Ineligible courier | Clear warning, selection cleared, confirmation disabled | 07-ineligible.png |
| Stale assignment conflict | Dialog closed; conflict notice and refreshed queue | 08-conflict.png |
| Assignment denied | Permission error visible; selection cleared | 09-assignment-denied.png |
| Wrong portal role | Forbidden view | 10-forbidden.png |

Screenshots are retained under `%TEMP%/zd092-visual/`; they are not committed build/test artifacts.
Read-only health probes found Keycloak discovery on port 8180 responding 200; the gateway
and S1/S2/S3 health URLs on ports 8080-8083 were unavailable. No application services were
started or production fixes applied. Therefore customer parcel creation and live gateway
assignment/reassignment curl acceptance remain unverified. The production parcel source
still fails closed as documented in F4, and seeded permission provisioning remains F2.

## Findings and follow-ups

| ID | Finding / evidence | Effect and next action |
|---|---|---|
| F1 | DispatchPermissions recognizes DISPATCH_REASSIGN, but current matrix/lifecycle endpoints require DISPATCHER only. Seed provisioning supplies no permission claim. | Policy inconsistency, not a defect or blocker to this focused automation. Preserve production behavior; @pavi3alnx and security/Keycloak owners decide enforcement/provisioning and reconcile docs. |
| F2 | Read/assign endpoints require DISPATCH_VIEW/DISPATCH_ASSIGN with no fallback; checked-in seeded dispatcher cannot pass them without external provisioning. | Do not claim real seeded-dispatcher success. Record runtime provisioning evidence before the manual journey; security/Keycloak owners own follow-up. |
| F3 | Workload assignmentCount includes completed proofs; it is not an active-capacity metric. | Tests/UI use the existing assignment-based contract. Lifecycle metric design remains separate. |
| F4 | Production UnavailableParcelSource returns 503 DISPATCH_SOURCE_UNAVAILABLE. | Isolated automation does not prove an integrated dispatch journey. Coordinate the approved source contract/adapter with #226 owner @Nilakshana23 and upstream owners. |
| F5 | No frontend reassignment workflow/API call; some older workload/permission-proposal text still says reassignment is absent. | Cover actual lifecycle API; record remaining UI under #244 and documentation reconciliation with relevant owners. |
| F6 | No documented picked-up/completed reassignment outcome or production source guarantee was available. | Keep this outcome unverified; #226/upstream contract owners must establish the policy and evidence. Do not invent or weaken tests. |

F1/F2 evidence on the base revision:

- `backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/security/DispatchPermissions.java:15,23-28`:
  recognized names, required top-level permissions array, exact match; no role-only fallback.
- `backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/dispatch/controller/DispatchQueueController.java:27,33,43,50,60`:
  class DISPATCHER rule; explicit view/assign method rules; lifecycle methods inherit the role rule.
- `infra/keycloak/seed-users.sh:71`: dispatcher seed grants DISPATCHER only.
- `infra/keycloak/realm-export.json:71-112`: audience, phone and role mappers, no permissions mapper.
- `docs/security/permission-matrix.md`: reassign and DELETE assignment rows specify D to D with no named permission.
- `docs/api/s3-dispatch-permissions-proposal.md`: provisioning unchanged, missing-claim denial,
  no fallback, reserved DISPATCH_REASSIGN text; this proposal predates the lifecycle endpoints.

## WARP section 24 item-by-item evidence

PASS below applies to the approved automation scope; it does not claim the outstanding
manual journey, runtime provisioning or status-specific lifecycle policy is verified.

| Criterion | Status | Evidence |
|---|---|---|
| Requirements implemented | PASS for approved automation | Scenario matrix a-i; 11 new browser cases and 12 new S3 cases; F1-F6 identify boundaries |
| Correct microservice owns business logic | PASS | Production logic unchanged; new backend scenarios exercise S3 |
| Architecture respected | PASS | Existing configuration, fixtures, clients and packages reused |
| Service boundaries maintained | PASS | S1 and parcel-port HTTP stand-ins; only disposable S3 tables accessed by added tests |
| Project conventions followed | PASS | Existing E2E layout; lint, strict E2E typing and application type-check passed |
| Only four primary roles | PASS | CUSTOMER, MERCHANT, COURIER, DISPATCHER fixtures; no role-model changes |
| No separate ADMIN primary role | PASS | No role or portal added |
| Dispatcher operational responsibilities supported | PASS within scope | Queue/workload/assignment browser journey; lifecycle API scenarios; integrated runtime F4 remains |
| Dispatcher administrative permissions enforced | PASS for approved policy | Role-only reassign/unassign preserved; view/assign checks unchanged; F1/F2 provisioning finding |
| Unauthorized access prevented | PASS | Backend 401/403 cases and courier detail ownership checks; browser guards |
| Database ownership respected | PASS | Added JDBC assertions restricted to S3's Testcontainers database |
| Required migrations included | N/A | No schema or migration changes |
| API contracts updated where necessary | N/A | No API contract changes |
| Kafka events handled correctly | PASS for persistence boundary | Assert delivery.reassigned/delivery.cancelled envelope IDs, types, parcel, actor and courier IDs; added tests do not claim broker delivery |
| Transactional Outbox consistency maintained | PASS | Production transactions unchanged; lifecycle record counts and unchanged-state snapshots on failures |
| Authentication enforced | PASS | Added HTTP 401 tests; existing signed-JWT and rebuilt gateway/Keycloak integration tests passed |
| Authorization enforced | PASS | Role/permission tests and cross-courier ownership tests passed |
| Sensitive information protected | PASS | Synthetic fixtures and scoped responses; no production data queried |
| Secrets not committed | PASS | Only approved test/report paths; synthetic tokens; no environment/configuration files |
| External input validated | PASS | Existing invalid-assignment/filter tests passed in the S3/full reactor suites |
| Relevant unit tests implemented | PASS by reuse | Existing unit coverage mapped above; 729 frontend tests and backend unit suites passed |
| Relevant integration tests implemented | PASS | New 12-case S3 class and existing PostgreSQL/gateway suites passed |
| Role-based tests included | PASS | CUSTOMER/MERCHANT/COURIER denial, anonymous denial and role-only dispatcher success |
| Relevant builds succeed | PASS | Production frontend build and all 9 backend reactor modules passed |
| Failure scenarios considered | PASS | 401/403/404/409/503 outcomes; conflict/ineligibility/guard failures preserve state |
| Relevant documentation updated | PASS | This matrix, commands, actual results and findings |
| Changes reviewed | PENDING | Independent @hephzibah-peries and requested owner reviews remain required |
| No unrelated modifications | PASS | Approved 9-file change including final verification evidence |
| Implementation summary provided | PASS | Scope, scenario matrix and verification results above |
| Remaining limitations documented | PASS | F1-F6; opt-in suite exclusion; manual/PR/review/merged-SHA evidence pending |

## Review and completion evidence

No production fix is included. Preserve the five-service/database ownership boundaries,
four primary roles, existing authentication and all existing tests. No API/event schema
changes require regeneration. WARP section 24 implementation/testing evidence is above;
manual verification, independent review and merged SHA are outstanding. Full-reactor checks passed.

| Required evidence | Status |
|---|---|
| Focused PR to develop, with Closes #167 | Push/PR creation authorized by user; PR URL tracked in GitHub |
| Independent approval by @hephzibah-peries | Pending |
| Backend/docs CODEOWNER review by @pirathee587 | Pending |
| @pavi3alnx additional reviewer; @Nilakshana23 informed | Pending PR workflow |
| Delegated local visual evidence | 10 fixture-browser screenshots inspected; integrated curl journey unverified |
| Exact merged commit SHA | Pending human merge; no merge performed here |

Do not mark #167 complete until the PR, passing checks, manual evidence, independent
sign-off and exact merged SHA have been recorded.
