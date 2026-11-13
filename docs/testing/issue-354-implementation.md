# Issue #354 implementation and verification

**Current verification (2026-11-13):** `ProofPostgresIntegrationTest` now passes
**30/30**, with zero failures, errors or skips. A verified stale-owner submission
race was corrected by rechecking both canonical owner IDs under a PostgreSQL
assignment row lock in the evidence transaction. The race regression and lock
retention/rollback tests pass. Existing assignment evidence remains **27/27**;
that suite was not rerun. Production dispatch, progress and real E2E acceptance
remain dependent on the contracts/policies listed below. See the final resumption
section for current commands and results; earlier sections are historical.

**Earlier memory gate (2026-11-13):** No Maven or tests launched. Windows performance
counters showed only 764 MiB, then 687 MiB available physical RAM, despite
approximately 10.25–10.39 GiB commit headroom. Physical memory remains unsafe for
the proof retry after the earlier native-memory failure. Assignment results stay
27/27; proof validation, ownership, immutability and the proof/reassignment race
remain pending execution. See the final memory-gate section below.

**Earlier executed verification:** Clean reactor compilation succeeded and
`AssignmentPostgresIntegrationTest` passed **27/27** on real PostgreSQL with the
invocation-only Docker API 1.56 override. The subsequent proof suite started its
database/migrations, then crashed with native-memory exhaustion before reporting
any completed tests. Further testing stopped as instructed. The proof race remains
unverified; Issue #354 is not fully complete. See the final verification section
below; earlier runs are retained as historical evidence.

Date: 2026-11-13 (Asia/Colombo). Developer: Nilakshana23; independent reviewer:
pavi3alnx. Review is pending. Branch:
`feature/354-dispatch-courier-lifecycle-and-proof-of-delivery`.
Inspected HEAD: `5a3aba3928620d8a671d6e4b213b18c2d4acf6d6`.
The initial working tree was clean. All work remains uncommitted. Only permitted
read-only Git commands were used; no branch, index, commit, remote or PR changes.

## Acceptance status

| Group | Classification | Completed work | Remaining acceptance |
|---|---|---|---|
| G02 | PARTIALLY COMPLETED | Reassignment repeats existing active/account and area validation and verifies parcel source identity. Existing negative unit tests passed. Assignment PostgreSQL suite now passes 27/27: parcel concurrency, cross-parcel capacity characterization, after-proof cancellation/reassignment, audit/outbox and recovery. Production source remains fail-closed. | Capacity policy/locking decision, after-proof cancellation response and prior-proof visibility require approval. Real S2 candidate/guarded-claim adapter remains unavailable. S3 HTTP scenarios now pass 22/22; gateway rerun remains unverified. |
| G03 | BLOCKED | Revalidated controller, S2 status authority and permissions; updated existing blocker document. Successful POD orchestration retained. | No approved S3 progress/failure endpoint, idempotency/conflict/evidence schema or dispatcher handoff. No new COURIER/SERVICE permission granted. No S3 progress/failure tests or live integration can be implemented against an absent contract. |
| G30 | PARTIALLY COMPLETED | Added blank recipient/reference and supplementary Unicode boundary unit cases; these passed in the original run. Added PostgreSQL tests for every immutable evidence column, note/reference-only conflicts, stale owner rejection and invalid-input no-write behavior. Existing duplicate/concurrent proof, foreign-owner and signed-JWT coverage reused. Converted the deterministic race characterization into a passing stale-owner rejection regression after the transactional lock fix. | Proof PostgreSQL suite now passes 30/30. The ownership-check/insert race is fixed and regression-tested; isolated HTTP/security verification is recorded in the final section. Evidence-field approval and real-service completion remain external dependencies. No stronger reference/recipient format or failed-delivery policy invented. |
| G31 | BLOCKED | Inspected existing isolated S3 and gateway ownership suites and E2E support; documented actual dependencies/scenario plan. | Production source, approved courier progress orchestration, provisioned real identity/completion client, Docker/Kafka and real S2/S4 integration. No CourierLifecycleIT success fixture or disabled placeholder created. |
| G36 | BLOCKED | Reused existing dispatcher HTTP/PostgreSQL/security scenarios; documented acceptance gaps. | Production candidate/guarded-claim provider and runnable real stack. No DispatcherAssignmentIT success fixture or runtime test adapter created. |

## Verification actually executed

From `backend`:

```powershell
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am verify
```

The first sandboxed attempt failed during POM resolution: Central DNS unavailable
for `spring-boot-dependencies:3.5.16`; no tests ran. The same command was retried
with sandbox escalation and resolved dependencies successfully. Final run:
**BUILD FAILURE**, 10:45 minutes, because Docker was unavailable. Production and
test sources compiled, including all added PostgreSQL tests. Class timestamps
were checked after the run to confirm compilation followed the final Java edits.

| Module | Reported tests | Passed | Assertion failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| Security starter | 62 | 62 | 0 | 0 | 0 |
| S3 dispatch/courier | 272 | 245 | 0 | 2 | 25 |
| Total | 334 | 307 | 0 | 2 | 25 |

The two errors are container startup failures in `AssignmentPostgresIntegrationTest`
and `AssignmentToCourierScenarioTest`, both reporting no valid Docker environment.
The 25 skips are `ProofPostgresIntegrationTest` (14),
`CourierWorkflowIntegrationTest` (10), and
`GatewayCourierOwnershipIntegrationTest` (1), using their existing
`disabledWithoutDocker` annotations. Class-level skipped parameterized methods do
not enumerate all parameter cases; these counts are Maven's actual output, not a
claim that their parameters executed. No tests were disabled or removed here.

Changed runnable suites: `DispatchServiceTest` **29/29**,
`SubmitProofRequestTest` **13/13**, `OutboxPublisherTest` **1/1**. Existing controller,
permissions and signed-JWT suites also passed in the full run.

`docker info --format '{{.ServerVersion}}'` failed: Docker configuration access was
denied in the sandbox and the engine named pipe was absent. The escalated Maven
run independently confirmed Testcontainers could not find Docker. Docker startup
or configuration was not modified.

`git diff --check` passed. No OpenAPI check was required: paths, DTOs, error codes,
response schemas and permissions did not change. No real E2E command was run;
Docker and production prerequisites are absent, and no committed candidate
contains these uncommitted changes. No placeholder SHA was executed.

Local Maven log: `backend/issue-354-verify.log` (ignored generated output).
Fresh run totals above come from that log, not historical target reports.

## Doubles and limits

- New capacity race uses real PostgreSQL count/insert/transactions with two service
  and repository instances, a count barrier, test-only ParcelSource and mocked S1.
  It deliberately characterizes overbooking; it is not a capacity fix or real E2E.
- New stale-owner/validation repository tests use real PostgreSQL, mocked S1 and
  a constructed JWT; they are not signature-validation tests. Existing signed-JWT
  suites cover authentication independently.
- New durable outbox retry case uses real S3 PostgreSQL/repository transactions;
  the publisher test mocks repository, transaction manager and Kafka. Stable replay
  identity is tested; live broker delivery and consumer deduplication are unverified.
- Cancellation's existing FK rejection is characterized without changing HTTP
  conflict semantics or adding retention/soft-delete policies. Reassignment keeps
  evidence but changes current assignment visibility; approval is still needed.

## Dependency owners

- S3 Nilakshana23 / reviewer pavi3alnx: capacity definition, after-proof lifecycle,
  evidence visibility, progress/failure endpoint and permissions/handoff decisions.
- #353 Jathuja with #351 pavi3alnx and S3: candidate enumeration and atomic guarded
  claim/release/recovery contract and production provider. Current detail lookup
  does not supply that guarantee.
- #350 kirusigan03: authenticated eligible courier identity and service-client
  provisioning, coordinating #352 infrastructure owner for the runnable stack.
- #357 hephzibah-peries with S2/S1: tracking hydration/initialization contract and
  live projection acceptance. S2 retains sole parcel lifecycle authority.

## Changed files

Under `backend/dispatch-courier-service/src/`:

- `main/java/com/zentro/dispatchcourier/dispatch/service/DispatchService.java`
- `test/java/com/zentro/dispatchcourier/courier/dto/SubmitProofRequestTest.java`
- `test/java/com/zentro/dispatchcourier/courier/repository/ProofPostgresIntegrationTest.java`
- `test/java/com/zentro/dispatchcourier/dispatch/repository/AssignmentPostgresIntegrationTest.java`
- `test/java/com/zentro/dispatchcourier/dispatch/service/DispatchServiceTest.java`
- `test/java/com/zentro/dispatchcourier/event/OutboxPublisherTest.java` (new)

Documentation:

- `docs/architecture/zd-057-failed-delivery-contract-blocker.md`
- `docs/testing/proof-validation.md` (new)
- `docs/testing/dispatcher-backend-acceptance.md` (new)
- `docs/testing/courier-lifecycle-e2e.md` (new)
- `docs/testing/issue-354-implementation.md` (new, this report)

Final expected Git status: six tracked modified files, five new files, no staged
changes. No unrelated existing changes were present or modified. Parent/build
POMs, shared support/contracts/security, migrations, other service production,
infrastructure and frontend are unchanged.

## Docker / PostgreSQL follow-up (2026-11-13)

### Original two errors: exact cause

Both `AssignmentPostgresIntegrationTest` and `AssignmentToCourierScenarioTest`
failed in Testcontainers `beforeAll` with `Previous attempts to find a Docker
environment failed. Will not retry`. This is Testcontainers' cached discovery
failure, not two independent test assertions or database failures. Host checks
outside the sandbox established:

- Docker client 29.8.1, context `desktop-linux`.
- No Docker Desktop process; `com.docker.service` stopped.
- Linux engine pipe `dockerDesktopLinuxEngine` absent. Sandboxed discovery also
  reported an absent `docker_engine` pipe and denied access to Docker config.
- S3 already sets `api.version=1.44` in its own Surefire configuration and pins
  Testcontainers 1.20.4. No evidence supports changing API compatibility settings.

Environment action performed with sandbox escalation:

```powershell
Start-Process -FilePath 'C:\Program Files\Docker\Docker\Docker Desktop.exe' -WindowStyle Hidden
docker info --format '{{.ServerVersion}}'
```

The engine subsequently returned `29.8.1`. No Docker context, service settings,
WSL configuration, shared infrastructure, POM or other-team file was changed.
Docker Desktop remains running. Host engine availability is restored; successful
Testcontainers/container/database startup is **not yet verified**.

### Follow-up attempts and actual results

All commands ran from `backend`, before the user's instruction to hold further
tests. The last retry had already exited when that instruction arrived.

The first selected-suite command used an unquoted
`-Dsurefire.failIfNoSpecifiedTests=false`; PowerShell split the argument and Maven
reported `Unknown lifecycle phase ".failIfNoSpecifiedTests=false"`. Zero tests
ran. Quoting the full property corrected the invocation, without repository edits.

Corrected command:

```powershell
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=AssignmentPostgresIntegrationTest,ProofPostgresIntegrationTest,AssignmentToCourierScenarioTest,CourierWorkflowIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' verify
```

This failed before tests with native JVM allocation failure (249760 bytes,
`hs_err_pid24708.log`). Windows had about 330 MB free committed memory. An
invocation-local `MAVEN_OPTS` limit of 128 MB and test heap limit of 192 MB also
failed before tests, allocating 910256 bytes (`hs_err_pid27696.log`). No processes
belonging to other work were stopped and no host memory configuration was changed.

When free committed memory rose to about 1.3 GB, this bounded retry was executed:

```powershell
$env:MAVEN_OPTS='-Xms32m -Xmx192m -XX:ReservedCodeCacheSize=32m -XX:ActiveProcessorCount=2'
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=AssignmentPostgresIntegrationTest,ProofPostgresIntegrationTest,AssignmentToCourierScenarioTest,CourierWorkflowIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DargLine=-Xms32m -Xmx256m -XX:ReservedCodeCacheSize=32m -XX:ActiveProcessorCount=2' verify
```

Result: **BUILD FAILURE**, 21.529 seconds; **4 reported class-level attempts,
0 passed, 0 assertion failures, 4 errors, 0 skips**. All four selected classes
failed during field reflection with `NoClassDefFoundError: JdbcTemplate`, caused
by `ClassNotFoundException: JdbcTemplate`, before PostgreSQL or container setup.
The upstream security module had no matching selected tests; no new security test
result is claimed. Latest log: `backend/issue-354-postgres.log`.

Read-only inspection of generated
`target/test-classes/.../ProofPostgresIntegrationTest.class` found the descriptor
`LJdbcTemplate;` and embedded `Unresolved compilation problems` stubs, including
unresolved Spring JDBC/transaction imports. This is invalid compiled output:
the source imports the fully qualified Spring type. Maven logged `Nothing to
compile - all classes are up to date`. Output timestamps also changed after the
run, consistent with another incremental compiler writing the same target tree;
the producing process was not established. Do not fix this by changing Spring
dependencies or source imports. A clean S3 compilation without competing output
writes is required before trusting the next result. No generated output was
deleted and no IDE settings were changed while tests are on hold.

### Reassignment-race test prepared, not executed

`ProofPostgresIntegrationTest.characterizesOwnershipCheckBecomingStaleBeforeProofInsert`
uses real PostgreSQL repositories and the existing service. A latch pauses after
the old assignment is read; an independent repository commits reassignment; a
second latch resumes the old courier's proof submission. Mocked S1 and a
constructed JWT isolate the persistence race. All waits are bounded.

Source inspection predicts the current service accepts the proof: ownership is
checked before the separately transactional insert, whose FK validates only the
parcel assignment's existence. The characterization explicitly records this as
a security gap, **not approved behavior**. It checks original submitting actor,
retained evidence and replacement assignment. It must not be counted as proof of
safe concurrent ownership. No production fix or new late-submission policy was
introduced in this focused test/environment follow-up.

### Verification still required after user resumes testing

1. With memory ready, prevent competing incremental output writes and clean only
   S3 generated build output (for example, `mvn --batch-mode --no-transfer-progress
   -pl dispatch-courier-service clean`), then rerun the quoted selected-suite
   reactor command above. This is a future command, **not executed** in this follow-up.
2. `AssignmentPostgresIntegrationTest`: independent-service same-parcel conflicts,
   cross-parcel courier capacity race, advisory locks, after-proof cancellation
   rollback/reassignment evidence retention, audit/outbox atomicity and recovery.
3. `ProofPostgresIntegrationTest`: concurrent identical/conflicting evidence,
   every immutable column, stale ownership, rejected-input no writes, leases and
   recovery, plus the new proof-submission/reassignment race characterization.
4. `AssignmentToCourierScenarioTest` and `CourierWorkflowIntegrationTest`: actual
   S3 HTTP/PostgreSQL assignment, ownership, after-proof and completion scenarios
   using their documented upstream doubles. No real S2 integration is implied.
5. The prior Docker-skipped `GatewayCourierOwnershipIntegrationTest` also remains
   unverified; it requires its existing gateway package/Keycloak prerequisites
   and is not included in the four-class command above.

This continuation changed only the S3 proof PostgreSQL test and relevant S3
documentation. Earlier uncommitted implementation and tests were preserved.
`git diff --check` passed. No new Git write, shared configuration, business-policy
or S2 contract change was made. G02/G30 remain PARTIALLY COMPLETED; G03/G31/G36
retain their contract blockers. Testing now waits for the user's memory-ready
instruction; no background Maven run remains.

## Latest resumption: clean build and individual integration verification

The user authorized resumption with a requirement to stop if native-memory
allocation failed again. Branch and the existing eleven changed files were
rechecked and preserved. Available memory initially measured 829440 KiB physical
and 585228 KiB committed; Docker Engine 29.8.1 was reachable. Existing Java
processes were identified as language servers, not Maven/Surefire. Only one Maven
invocation was run at a time. No language-server process was stopped.

### Commands and results

All commands below ran from `backend`, with sandbox escalation. The common
invocation-local Maven setting was:

```powershell
$env:MAVEN_OPTS='-Xms16m -Xmx128m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2'
```

| Step | Command / log | Actual result |
|---|---|---|
| S3 generated-output clean | `mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service clean`; `issue-354-clean.log` | BUILD SUCCESS; no tests |
| Assignment after S3-only clean | Assignment command below without the API override; `issue-354-assignment.log` | Compilation failure, zero tests: shared security generated bytecode referenced unqualified `ObjectMapper`; no shared source was edited |
| Clean dependency reactor | Same assignment command without API override, with `clean verify`; `issue-354-assignment-clean.log` | Clean javac compilation succeeded for security/S3/tests; Docker discovery succeeded. PostgreSQL log readiness timed out: 1 class startup error, zero executed scenario assertions |
| Native Docker API | Exact assignment command below; `issue-354-assignment-native-api.log` | BUILD SUCCESS, 31.520 seconds; 27 tests, 0 failures/errors/skips |
| Proof alone | Exact proof command below; `issue-354-proof-before.log` | BUILD FAILURE: native-memory crash after PostgreSQL startup and migrations. Maven reported 0 completed tests; no proof assertions counted as passed |

Successful assignment command:

```powershell
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=AssignmentPostgresIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Ddocker.api.version=1.56' '-DargLine=-Xms16m -Xmx192m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2' verify
```

Proof command, which crashed:

```powershell
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=ProofPostgresIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Ddocker.api.version=1.56' '-DargLine=-Xms16m -Xmx192m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2' verify
```

### Environment findings and actions

Cleaning S3 alone exposed invalid generated classes in the shared security
dependency. Rebuilding its generated artifacts through `-am clean verify` resolved
the compilation errors without changing shared code, POMs or configuration.
The clean run compiled 27 S3 production and 21 test source files with javac.
This confirms the race test compiles, but does not establish its execution.

The existing API 1.44 setting discovered Docker but timed out waiting for
PostgreSQL log readiness, reporting `Container exited with code 0` and no logs.
A disposable CLI PostgreSQL probe, without published ports, became ready within
seconds. The isolated Maven retry using this engine's advertised API 1.56 then
passed. This establishes a working host-specific invocation; it does not identify
the precise underlying old-API/docker-java transport defect or justify raising
the project-wide API default. No permanent configuration change was made.

The CLI probe was stopped and removed. The later proof JVM crashed allocating
131088 native bytes (`Chunk::new`); error file:
`backend/dispatch-courier-service/hs_err_pid24368.log`. Surefire reported a fork
termination, exit code 1. The exact proof-test PostgreSQL container left by the
crash was inspected as `postgres:16-alpine` and removed by its full container ID.
No unrelated containers were removed. No Maven/tests were run after this crash.

### Confirmed PostgreSQL evidence and remaining gaps

`AssignmentPostgresIntegrationTest` **27/27** verifies existing unique-parcel
conflicts across independent service instances, transaction/advisory-lock
behavior, assignment counts, audit/outbox behavior, durable outbox retry,
cancellation rollback preserving proof in all completion states, and reassignment
retaining immutable evidence. The capacity test deliberately reproduces two
cross-parcel assignments exceeding a configured limit of one. Its passing result
is evidence of the race, not a concurrency fix or an approved capacity policy.
Capacity remains the existing lifetime-row measure; approved counted states,
threshold and reassignment semantics are still needed before changing it.

| Required suite | Latest verified status |
|---|---|
| AssignmentPostgresIntegrationTest | 27 passed, 0 failures/errors/skips |
| ProofPostgresIntegrationTest | Cleanly compiled; PostgreSQL/migrations started; JVM crashed, 0 completed tests. All proof assertions including reassignment race still require execution |
| AssignmentToCourierScenarioTest | Compiled; not rerun after crash. Prior startup error is historical, not a current passing result |
| CourierWorkflowIntegrationTest | Compiled; not rerun after crash; prior Docker skips remain unverified |
| GatewayCourierOwnershipIntegrationTest | Compiled; not rerun; existing real gateway/Keycloak packaging prerequisites still required |
| CourierLifecycleIT | Not created/run: production candidate/guarded-claim adapter and approved courier progress authority absent |
| DispatcherAssignmentIT | Not created/run: production candidate/guarded-claim integration absent |

Current-source inspection still finds no candidate enumeration or guarded claim
provider in S2. `UnavailableParcelSource` is retained. Existing S2/S3 progress and
failure authority has not changed; the approvals recorded under #353/#351 and
the failed-delivery blocker remain required. #350 identity/service-client and
#352/#357 stack/projection integration are not established by isolated S3 tests.

No production changes were added in this resumption: unresolved contracts and
unexecuted proof-race assertions do not justify speculative fixes. The existing
S3 changes remain intact. Current G02/G30 status is PARTIALLY COMPLETED;
G03/G31/G36 are BLOCKED. The 27 assignment passes are new evidence, not a full
reactor or real-service E2E pass. Full verification and the remaining individual
suites must wait for sufficient committed memory. `git diff --check` passed.

## Subsequent memory-gated verification check (2026-11-13)

The user requested continuing the remaining individual suites only when memory
is sufficient, without rerunning the passing assignment suite. Before launching
anything, host memory was read through `Win32_OperatingSystem` and
`Win32_PerfFormattedData_PerfOS_Memory` with sandbox escalation:

| Metric | Observed values |
|---|---|
| Total physical memory | 7896 MiB |
| Available physical memory | 15 MiB initially; 610 MiB on the subsequent performance-counter sample |
| Commit limit | 32472 MiB |
| Current committed memory | 32093 MiB initially; 32146 MiB on the subsequent sample |
| Available commit headroom | 379 MiB initially; approximately 326 MiB subsequently |
| Commit utilization | 98% reported by Windows |

These are successive samples, not an atomic snapshot. Physical availability was
volatile and committed-memory headroom remained critically low. Docker Engine
29.8.1 was reachable. Process inspection found no Maven or Surefire command line.
No existing Java/user process was stopped.

**No Maven, compiler or integration test was launched in this continuation.**
The 27 passing assignment PostgreSQL tests were not rerun. No new failure,
pass or skip count is claimed. Memory remains the immediate verification blocker;
the next proof run must retain the conservative settings and host-specific
`-Ddocker.api.version=1.56` override already recorded above, only after sufficient
physical and committed memory is available.

Pending suites remain `ProofPostgresIntegrationTest`,
`AssignmentToCourierScenarioTest`, `CourierWorkflowIntegrationTest`, and
`GatewayCourierOwnershipIntegrationTest`, individually. Proof immutability,
ownership, conflicting evidence and the submission/reassignment race are still
unverified by execution. Approved production behavior and all existing changes
were preserved. Only this report was updated in this continuation; no Git write
operation or host configuration change occurred. `git diff --check` passed.

## Cautious proof-only resumption: memory gate (2026-11-13)

Before testing, two successive Windows `Get-Counter` samples reported:

| Metric | First sample | Second sample |
|---|---:|---:|
| Available physical RAM | 764 MiB | 687 MiB |
| Committed bytes | 20,900,319,232 | 20,748,963,840 |
| Commit limit bytes | 31,901,933,568 | 31,901,933,568 |
| Commit headroom | approximately 10.25 GiB | approximately 10.39 GiB |

These are successive samples, not an atomic snapshot. The sandbox denied the
`Win32_OperatingSystem` CIM query; the Windows memory performance counters
succeeded. Commit headroom has recovered, but physical availability remains
below 0.75 GiB and declined between samples. Given the previous proof JVM's
native-memory allocation failure, this is insufficient margin to safely launch
Maven, its test fork and PostgreSQL. The memory gate therefore blocked execution.

**No Maven or test suite was launched.** No new pass, failure or skip count is
claimed. `AssignmentPostgresIntegrationTest` retains its existing **27/27** result
and was not rerun. Proof validation, ownership, every immutable evidence column,
and `characterizesOwnershipCheckBecomingStaleBeforeProofInsert` remain unverified
by execution. The race test characterizes acceptance of stale-owner evidence;
even a future passing characterization would not establish that the gap is fixed.

When resources permit, the next authorized test is only
`ProofPostgresIntegrationTest`, using the conservative JVM settings and
`-Ddocker.api.version=1.56` invocation recorded above, with no parallel suites.
Existing source changes were preserved. Only this report was edited; no branch,
commit, index, remote, PR or host configuration changes were made.

## Autonomous completion audit and proof correction (2026-11-13)

### Scope and local dependency audit

Read WARP.md, the user's five-gap requirements, this report, S3 dispatch/proof
services, repositories, controllers, migrations and relevant tests; rechecked S2
booking/status controllers, its lifecycle/role contract, the canonical parcel
proto, #353 characterization, #351 contract inventory, identity provisioning docs,
tracking projection docs and disposable E2E support. GitHub issue retrieval failed
(cache miss), and `gh` is unavailable; no new remote issue approval is claimed.
The user's supplied requirements and local issue evidence were used. No fetch,
pull, branch, index, commit, push or PR action was performed.

| Gap | Current implementation and exact remaining decision |
|---|---|
| G02 | Existing reassignment eligibility/source-ID checks and assignment/audit/outbox transactions retained. Existing assignment suite passed 27/27 previously and was not rerun. Capacity is NOT atomic: its characterized cross-parcel race remains. Code defaults to ten lifetime rows, while workload docs/OpenAPI say no threshold is configured. Owner/reviewer must approve the counted states and threshold, completed-proof treatment and reassignment/replay semantics before changing enforcement. Cancellation after proof still rolls back on FK rejection; its public conflict response and retained-history policy are unapproved. Reassignment retains proof and current-owner visibility; that visibility policy remains pending. No approved S2 candidate/guarded-claim provider exists locally; UnavailableParcelSource remains fail-closed. |
| G03 | No S3 courier progress/failure command is implemented. S2 PATCH /api/v1/parcels/{parcelId}/status remains authoritative: DISPATCHER can perform legal transitions; SERVICE can only acknowledge DELIVERED; COURIER is forbidden. Need approved S3 paths/request/response, allowed source/target states, free-text versus coded reasons, optional/required evidence, replay/conflict keys, dispatcher handoff and permission mapping. No new authority was granted. |
| G30 | Corrected the verified stale-owner submission race using an assignment FOR SHARE lock and both canonical owner IDs inside the proof transaction. Real PostgreSQL suite passes 30/30, including invalid-input no-write checks, immutable columns, conflicts, concurrent submissions, ownership, rollback and recovery. Original evidence fields remain proposed pending owner/reviewer approval; live provider completion is not established by these tests. |
| G31 | CourierLifecycleIT is not implemented because a real booking cannot enter a production S3 eligible queue, and courier progress/handoff lacks an approved contract. The existing real-service scenario is retained in courier-lifecycle-e2e.md; no artificial business data or placeholder success test was added. |
| G36 | DispatcherAssignmentIT is not implemented: authenticated production candidate enumeration and guarded claim/release/recovery remain absent. Isolated HTTP fixtures are not a real booking-to-queue journey. |

#350 supplies existing courier REST seams and dispatcher claim provisioning;
closure/provisioning docs do not establish a running confidential POD client.
#351 supplies existing gateway routes/security and the contract inventory, not the
missing claim/hydration contracts. #352 provides disposable stack support; Docker
29.8.1/API 1.56 was verified by the proof run. #353 still has only the declared
GetParcel proto and booking/detail/status REST operations, without candidate or
claim APIs. #357 tracking acceptance still requires approved initialization and
real event/projection operation. None of those teams' production code, shared
contracts, fixtures, gateway, frontend or CI was edited.

### Defect correction and compatibility

`ProofOfDeliveryService.submit` still resolves identity and performs its original
ownership/validation ordering. It forwards the normalized request, profile ID and
app-user ID to `ProofRepository.saveOwned`. That public transactional entry point
locks the current assignment FOR SHARE, rejects missing assignment with existing
404 or a mismatched owner with existing 403, then saves evidence in that same
transaction. No HTTP runs while the lock is held. Reassignment/cancellation must
wait for an accepted proof commit; a reassignment that commits first denies the
stale submission. Existing post-commit reassignment and immutable proof behavior
remain unchanged. No API schema, permission, migration or completion-worker policy
was changed.

The former race characterization now asserts rejection and unchanged proof,
assignment, audit and outbox after the winning reassignment. Additional tests
cover both canonical IDs, missing assignment, outer rollback and reverse ordering:
a competing reassignment times out with PostgreSQL SQLSTATE 55P03 while the proof
transaction holds its lock, then succeeds after commit without changing evidence.
Controller tests verify canonical IDs/normalized evidence reach saveOwned. New
signed RS256/JWKS tests deny proof reads and submissions for either foreign owner
ID, using identity/repository doubles separately from the PostgreSQL race tests.

### Memory and execution discipline

Initial counters: available physical 825 MiB; committed 19,928,829,952 bytes of
31,901,933,568 bytes (about 11.15 GiB headroom). Later pre-run samples had 877,
893 and 881 MiB available with about 11.75 GiB headroom. Paging was active.
Process inspection found existing Java PID 4284, Docker Desktop/backend and
vmmemWSL; none was stopped. The decision to attempt a bounded run used stable
physical availability, substantial recovered commit headroom, process inspection
and conservative JVM limits together, not a free-RAM cutoff alone. Memory was
sampled during builds and forks; no native allocation failure occurred in the
completed proof runs. Only one Maven invocation and one test suite/fork ran at a
time; no parallel build or assignment-suite rerun was used.

### Commands and intermediate results

From backend, invocation-local settings only:

```powershell
$env:MAVEN_OPTS='-Xms16m -Xmx128m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2'
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=ProofPostgresIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Ddocker.api.version=1.56' '-DargLine=-Xms16m -Xmx192m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2' clean test
```

| Attempt / backend log | Actual result |
|---|---|
| issue-354-proof-resume.log | Sandboxed dependency access denied before compilation/testing; zero tests |
| issue-354-proof-resume-host.log | Incremental generated classes invalid: NoClassDefFoundError JdbcTemplate; 1 class startup error, zero scenario assertions |
| issue-354-proof-clean-resume.log | Clean javac succeeded; 30 tests, 29 passed and 1 assertion failure: new lock test assumed Spring CannotAcquireLockException but actual wrapper was UncategorizedSQLException with SQLSTATE 55P03. Corrected test to assert the exact PostgreSQL state; production lock worked |
| issue-354-proof-final.log | Incremental retry again reused invalid JdbcTemplate bytecode: 1 class startup error, zero scenario assertions |
| issue-354-proof-final-clean.log | Clean reactor BUILD SUCCESS, 50.807 seconds; ProofPostgresIntegrationTest 30 passed, 0 failures/errors/skips |

Clean builds replaced generated outputs only. No IDE/compiler configuration or
shared source was altered. Incremental-output interference remains an environment
limitation; clean javac runs are the evidence used here. Historical generated
Surefire reports may be overwritten by later clean builds; the named backend logs
retain the actual run results.

### Files changed in this continuation

- S3 production: courier/repository/ProofRepository.java and courier/service/ProofOfDeliveryService.java.
- S3 tests: courier/controller/CourierDeliveryControllerTest.java, courier/repository/ProofPostgresIntegrationTest.java and security/ZeroTrustJwtSecurityTest.java.
- Owned docs: docs/api/proof-of-delivery.md; docs/testing/proof-validation.md, courier-lifecycle-e2e.md, dispatcher-backend-acceptance.md and this report.

All previously uncommitted source/tests/docs were retained. Remaining capacity,
after-proof lifecycle and progress policies cannot be safely inferred from broad
acceptance goals. No full-issue completion or real-service E2E success is claimed.

### Isolated validation, authorization and recovery result

`issue-354-isolated-final.log`: **BUILD SUCCESS**, 2m21s, **247 passed,
0 failures/errors/skips**. Sequential forks used the same JVM limits above:

```powershell
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=SubmitProofRequestTest,CourierDeliveryControllerTest,ProofCompletionWorkerTest,ParcelStatusClientTest,ParcelStatusClientResilienceTest,ServiceTokenProviderTest,ServiceTokenProviderResilienceTest,IdentityCourierClientTest,IdentityCourierClientResilienceTest,ZeroTrustJwtSecurityTest,DispatchServiceTest,DispatchQueueControllerTest,DispatchPermissionsTest,OutboxPublisherTest,ExceptionHandlersTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Ddocker.api.version=1.56' '-DforkCount=1' '-DreuseForks=false' '-Djunit.jupiter.execution.parallel.enabled=false' '-DargLine=-Xms16m -Xmx192m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2' clean test
```

| Suite | Passed |
|---|---:|
| CourierDeliveryControllerTest | 27 |
| SubmitProofRequestTest | 13 |
| IdentityCourierClientResilienceTest / IdentityCourierClientTest | 24 / 11 |
| ParcelStatusClientResilienceTest / ParcelStatusClientTest | 21 / 15 |
| ProofCompletionWorkerTest | 5 |
| ServiceTokenProviderResilienceTest / ServiceTokenProviderTest | 14 / 9 |
| DispatchQueueControllerTest / DispatchServiceTest | 28 / 29 |
| OutboxPublisherTest / ExceptionHandlersTest | 1 / 2 |
| DispatchPermissionsTest / ZeroTrustJwtSecurityTest | 18 / 30 |

These cover HTTP slices, actual signature/issuer/audience checks with local JWKS,
current permissions and canonical ownership, production client timeout/error
mapping using local stubs, worker recovery and publisher recovery doubles. They
do not establish new S3 progress endpoints, live S2 authorization or broker E2E.
There is no Java formatter/linter plugin configured in the parent POM; clean javac
and `git diff --check` are the applicable source checks. Existing deprecation and
unchecked-operation warnings were reported; no dependency/build rule was changed.

### Existing S3 HTTP/PostgreSQL scenarios

`issue-354-http-final.log`: **BUILD SUCCESS**, 1m05s; **22 passed,
0 failures/errors/skips**: AssignmentToCourierScenarioTest 12/12 and
CourierWorkflowIntegrationTest 10/10. The following command ran with the same
MAVEN_OPTS above; forkCount=1/reuseForks=false kept suites sequential:

```powershell
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=AssignmentToCourierScenarioTest,CourierWorkflowIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Ddocker.api.version=1.56' '-DforkCount=1' '-DreuseForks=false' '-Djunit.jupiter.execution.parallel.enabled=false' '-DargLine=-Xms16m -Xmx192m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2' clean test
```

These exercise real S3 HTTP/security/persistence, concurrent HTTP replays, canonical
courier visibility, invalid/conflicting operations, assignment audit/outbox and
persisted worker completion/restart behavior. S1, S2, parcel-source and JWT-decoder
seams are doubled as declared by those existing fixtures. They are not real
CourierLifecycleIT or DispatcherAssignmentIT acceptance.

GatewayCourierOwnershipIntegrationTest was inspected but not launched in this
continuation. It adds Keycloak without a bounded test heap and a separate 256 MiB
maximum-heap gateway alongside Maven, S3, PostgreSQL and the existing language
server. The successful single-service runs still showed active paging and only
676-990 MiB physical availability. That larger launch profile is not established
safe by the recovered commit headroom or the smaller tests' success. No fixture,
gateway or global memory setting was changed to force it through. Its gateway JAR
exists, but live gateway/Keycloak ownership remains UNVERIFIED in this resumption;
the actual signed-JWT/JWKS slice tests passed separately. Full real-service E2Es
were not launched because their production contracts remain absent, independently
of memory. No skipped-test count is assigned to commands that were not run.

### Final focused build and acceptance status

The final proof concurrency cases were routed through saveOwned, so both identical
and conflicting concurrent submissions exercise the production ownership-locking
path. Final command (same invocation-local MAVEN_OPTS as above):

```powershell
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am '-Dtest=ProofPostgresIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Ddocker.api.version=1.56' '-DforkCount=1' '-Djunit.jupiter.execution.parallel.enabled=false' '-DargLine=-Xms16m -Xmx192m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=24m -XX:ActiveProcessorCount=2' clean verify
```

`issue-354-proof-verify-final.log`: **BUILD SUCCESS**, 36.637 seconds;
**30 passed, 0 failures/errors/skips**. Clean javac and JAR/repackage succeeded for
the selected S3/dependency reactor. This is a focused verify, not a full backend
reactor pass. Together with the isolated 247 and HTTP 22 results, **299 distinct
current focused cases passed**; repeated proof retries are not added to that total.
The earlier assignment **27/27** remains historical passing evidence, not newly
rerun tests. No newly implemented behavior is left uncompiled or without its
focused passing tests. Live gateway ownership and real upstream acceptance remain
unverified as stated above.

| Gap | Final status | Implemented and tested | Blocked / not implemented / unverified |
|---|---|---|---|
| G02 | PARTIAL | Existing eligibility/source checks, assignment conflicts, audit/outbox and rollback; 27 earlier repository passes; 12 current HTTP scenario passes plus unit/security coverage | Atomic capacity NOT implemented pending conflicting policy resolution; cancellation/late reassignment decisions pending; production S2 adapter absent |
| G03 | BLOCKED | Existing successful POD client/worker/security behavior retained and tested | Courier progress/failure endpoints, retry contract and dispatcher handoff NOT implemented: approved S2/S3 authority/shape/permission contracts absent |
| G30 | PARTIAL | Validation, canonical ownership, stale-owner race correction, immutability, duplicates/conflicts, concurrency, rollback/no-write, retry/completion consistency: proof 30/30, isolated tests and HTTP 10/10 passed | Existing proposed evidence-field approval and authenticated live provider completion remain external acceptance limits; gateway/Keycloak rerun UNVERIFIED |
| G31 | BLOCKED | Real-service scenario and exact dependency list preserved in owned docs | CourierLifecycleIT NOT implemented/run: production queue/claim and courier progress contracts absent; live identity/completion and tracking readiness unverified |
| G36 | BLOCKED | Existing isolated dispatch HTTP, security and persistence evidence reused | DispatcherAssignmentIT NOT implemented/run: production authenticated candidates and guarded claim/release/recovery absent |

Final memory sample after execution: 885 MiB available physical; committed
20,216,360,960 of 31,901,933,568 bytes (about 10.88 GiB commit headroom). No native
allocation failure occurred in this continuation. No process or service was killed
and no global settings were changed. All Maven invocations have ended.

Final read-only Git inspection confirms the original feature branch, preserved
pre-existing changes and only the additional owned paths listed above. Nothing
was staged or committed. `git diff --check` passed after final documentation edits.
Issue #354 is **not fully complete**: the blocked/unimplemented acceptance criteria
above require approved contracts/policies, not invented APIs or passing doubles.
