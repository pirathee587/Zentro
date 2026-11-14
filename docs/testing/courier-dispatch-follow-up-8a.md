# Courier / Dispatch / Dispatcher follow-up — Issue #359

Audited 2026-11-14 (Asia/Colombo). Branch:
`feature/359-courier-dispatch-acceptance-audit`, based on verified
`HEAD == origin/develop == 1f1b766d00f18c8eae788a1f399b34fb88e1bb0c`.
Scope is acceptance evidence and coordination. No domain production, policy,
authorization, fixture or domain scenario implementation was changed.

## Evidence and current execution

The supplied 299/299, 326 total, packaging PASS and zero memory crashes are
historical audit claims. The exact historical test selection was not supplied;
those totals are not asserted as a current rerun.

From `backend`, executed:

```sh
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am verify
```

First exit: **1**. Shared security failed before S3 ran:
`HttpPropagationIntegrationTest` could not create its local HTTP server,
`java.net.SocketException: Operation not permitted`. This is a sandbox failure,
not evidence of a courier production defect. Initial Docker probe similarly
failed creating `/run/user/1000/snap.docker`; an outside-sandbox probe succeeded.

Same Maven command outside the sandbox: **exit 0, BUILD SUCCESS**.
S3 **327 tests**, shared security **62 tests**, **389 total**, zero failures,
errors or skips. Packaging/repackage passed. Assignment PostgreSQL **27/27**;
proof PostgreSQL **30/30**; S3 HTTP scenarios **22/22**; gateway ownership **1/1**.
No process crash was reported by this run; no independent memory profiling was done.
Environment: Linux amd64, OpenJDK 21.0.12.1, Maven 3.8.7, Docker 29.8.0.
Finished 2026-11-14 18:47:33 +05:30, duration 2:14.

The workspace advanced from `b7fc2f7` to the above SHA during investigation.
`git diff --quiet b7fc2f7 HEAD -- backend/dispatch-courier-service
backend/libs/zentro-security-starter backend/pom.xml` returned 0: all source,
tests and build inputs in the tested reactor were unchanged. This is focused
reactor evidence, not a full-backend candidate release certification.

Retained [suite counts](evidence/courier-dispatch-8a-tests.json),
[command/result excerpts](evidence/courier-dispatch-8a-execution.txt), and
[GitHub issue/comment snapshots](evidence/courier-dispatch-8a-github.json).
Raw local logs: `/tmp/zentro-8a-maven.log` and
`/tmp/zentro-8a-maven-escalated.log`; full Surefire XML remains in each tested
module's `target/surefire-reports`. Raw logs/XML are not committed because they
contain test request payloads; retained counts deliberately exclude payloads.

## GitHub and source reconciliation

Read issue bodies and all comments for [#132](https://github.com/pirathee587/Zentro/issues/132),
[#227](https://github.com/pirathee587/Zentro/issues/227),
[#226](https://github.com/pirathee587/Zentro/issues/226),
[#354](https://github.com/pirathee587/Zentro/issues/354),
[#353](https://github.com/pirathee587/Zentro/issues/353) and
[#359](https://github.com/pirathee587/Zentro/issues/359). All returned empty
comment lists; there is no later comment approval to infer.

[PR #369](https://github.com/pirathee587/Zentro/pull/369) merged as `72408ff`;
its body explicitly retains G02/G30 partial and G03/G31/G36 blocked. Its branch
`feature/354-dispatch-courier-lifecycle-and-proof-of-delivery` still exists.
[CI run 43461823979](https://github.com/pirathee587/Zentro/actions/runs/43461823979)
on PR head `65e70d6` succeeded, including backend checks, container builds and
dependency scan. That historical green run does not verify absent scenario classes.
The workflow query for current `1f1b766` returned no runs; no current-head CI
approval is claimed.

#353 is closed, but [PR #365](https://github.com/pirathee587/Zentro/pull/365)
only adds owner-scoped parcel retrieval and deterministic merchant sorting.
Current `ParcelBookingController` has GET `/{id}`; older claims that S2 has no
detail GET are stale. A detail read does not enumerate candidates or guard
eligibility through assignment commit. Searching production sources and proto
contracts still finds only `UnavailableParcelSource implements ParcelSource`;
no `ListDispatch` or `ClaimForDispatch` implementation was found.

## Blocker ledger (priority order)

| Task / status | Verified reason and missing dependency | Responsible authoritative owner | Reproduction / actual result | Objective unblock condition / next action | Evidence |
|---|---|---|---|---|---|
| G03 — BLOCKED | `CourierDeliveryController` exposes reads and POST proof only. No courier progress/failure command. S2 `ParcelStatusController` accepts DISPATCHER/SERVICE; `ParcelLifecycle` restricts SERVICE to successful DELIVERED completion. No approved endpoint, source states, failure evidence/reason, retry conflict or dispatcher handoff contract. | @Nilakshana23 #354; coordinate @Jathuja #353 and @pavi3alnx #351 for S2/contracts/security. | Source route inspection finds no progress mapping. Full S3 verification passes existing tests; there is no runnable missing-endpoint acceptance scenario. No invented HTTP error is claimed. | Owners record approved paths/schema/authority/transitions/replay/handoff; #354 implements orchestration and owner/illegal-state/conflicting-retry tests; run real S2 integration. | #132, #354, PR #369; `courier/controller/CourierDeliveryController.java`; S2 `parcel/entity/ParcelLifecycle.java`. |
| G31 — BLOCKED | Real booking cannot enter S3 assignment through a production candidate/guarded source. G03 progress contract is also absent. `CourierLifecycleIT` does not exist; existing workflow doubles upstreams. | @Nilakshana23 #354 owns scenario; @Jathuja #353 provider; @pavi3alnx #351 contracts; @kirusigan03 #350 identity; @Niro0526 #352 environment; @hephzibah-peries #357 projection. @pirathee587 #359 shared support/evidence only. | `productionSourceFailsClosedForQueueAssignmentReassignmentAndCancellation` passes with `DISPATCH_SOURCE_UNAVAILABLE`, no identity/write calls. Gateway ownership passes with seeded assignments, not real booking. | Production source/claim/recovery plus approved progress exist; provision actual canonical courier and SERVICE completion identity; execute disposable real gateway→S1/S2/S3→Kafka/S4 flow, foreign-owner/retry/restart/terminal assertions. | #227, #354, PR #369; `DispatchServiceTest`; [scenario plan](courier-lifecycle-e2e.md). |
| G36 — BLOCKED | Candidate enumeration and guarded assignment/release/recovery provider absent; production `UnavailableParcelSource` explicitly throws 503. `DispatcherAssignmentIT` does not exist. | @Nilakshana23 #354 scenario/adapter; @Jathuja #353 provider; @pavi3alnx #351 contract; #350/#352 as above. #359 orchestrates shared acceptance only. | Same production-source test verifies fail-closed queue/assign/reassign/cancel. Isolated assignment HTTP tests pass with source/identity doubles. | Approved provider and S3 adapter deliver booking→queue→single assignment→queue removal→exact courier visibility; verify real role denials, disabled courier, stale claims, outages/recovery and audit/outbox once. | #226, #354, PR #369; [dispatcher plan](dispatcher-backend-acceptance.md). |

No live journey was attempted against fabricated assignment data to bypass these
production blockers. Docker availability is now verified; historical claims that
Docker itself is unavailable are not current blockers. Provisioned real completion
credentials, full-stack health and event consumption remain **unverified**, not
proven absent. Owners must demonstrate these after the source/contract gates clear.

Independent work can continue for all three rows: retain existing negative and
persistence characterization, expected-result plans and credential/configuration
requirements. Domain owners implement their scenario files; Main 10 can extend
shared support only against approved interfaces. No disabled success placeholders
or competing lifecycle logic are needed.

## Backend acceptance matrix

| Task | Existing implementation / passing current tests | Remaining gap and tests required | Owner / dependencies | Execution and reviewer evidence / unblock |
|---|---|---|---|---|
| G03 | Successful proof path; ownership/security tests retained | Owner progress, legal transitions, foreign owner, invalid reason, duplicate/conflicting retry, atomic S2 history/event and dispatcher handoff | #354; #353/#351 approval, G02 | BLOCKED; route/authority inspection above. Require approved contract, implementation and real-service evidence. |
| G31 | Workflow HTTP/PostgreSQL 10/10; gateway/Keycloak ownership 1/1 | Real booking/assignment/progress/proof→DELIVERED→Kafka/S4, unauthorized courier, restart and terminal conflict | #354 scenario; #359 support; G02/G03, #350/#351/#352/#357 | BLOCKED; no lifecycle IT executed. Doubles and direct assignment seeding limit existing suites. |
| G36 | Assignment HTTP 12/12; dispatch controller 28/28; signed-JWT security 30/30 | Real queue reconciliation, claim/release/recovery, exact courier visibility, role/failure paths | #354/#353/#351; identity/infrastructure | BLOCKED; no dispatcher IT executed. Production-source regression confirms unavailable dependency. |
| G02 | Assignment PostgreSQL 27/27; service 29/29; authorization, canonical ownership, inactive/foreign/mismatched source, conflict/no-write, audit/outbox and rollback tests | Production adapter absent. Capacity precheck is outside insert transaction; `differentParcelsCanExceedConfiguredCourierLimitAcrossIndependentServices` passes by demonstrating 2 assignments at limit 1. Reassign checks area/account but no capacity. Cancellation after proof rolls back on FK; public conflict/history policy and late-proof visibility approval pending. Require approved atomic capacity and real adapter/two-instance tests. | @Nilakshana23 #354 production; @Jathuja #353 source; #351 contracts; policy owner/reviewer approval | PARTIAL. Green characterization does not establish capacity enforcement. Preserve these tests; #354 resolves policy and implements approved fix, then #359 verifies integration. |
| G30 | Proof PostgreSQL 30/30; request boundaries 13/13; courier controller 27/27; completion worker 5/5. Immutable evidence, invalid/no-write, duplicate/conflict, both canonical IDs and stale-owner lock covered | Real proof submission via authenticated production booking/assignment, missing/invalid/duplicate/conflicting/unauthorized cases, persistence and actual S2/Kafka/S4 effects; evidence-field approval pending | @Nilakshana23 #354; G02/G03 for complete lifecycle; #350 SERVICE identity; #353/#357 downstream | PARTIAL / live verification pending. Real PostgreSQL is verified; S1/S2 HTTP doubles are not live provider verification. Require real-service evidence and independent approval. |

## Issue #359 progress report extension

### Courier / Dispatch / Dispatcher Follow-up

Supplied audit baseline:
- Newly verified tests: 299/299 passed
- Build & Packaging: PASS
- Memory crashes: 0
- Total passing results including assignment tests: 326
- Revalidated in current session: NO (exact historical selection unavailable).
  Current focused execution: 327 S3 + 62 shared security = 389 passes; packaging PASS.

G03 — Courier Progress:
- Status: BLOCKED.
- Verified blocker: absent approved S3 progress/failure contract and implementation.
- Responsible owner: @Nilakshana23 #354 with #353/#351.
- Unblock condition: approved authority/schema/retry/handoff, implemented and real-service verified.
- Evidence: blocker ledger, #132, #354, PR #369.

G31 — Courier Lifecycle E2E:
- Status: BLOCKED.
- Verified blocker: unavailable production source plus missing G03 contract.
- Responsible owner: @Nilakshana23 scenario; #359 shared support only.
- Unblock condition: production queue/claim/progress and real identity/completion/projection journey passes.
- Evidence: #227, production-source regression, current suite counts.

G36 — Dispatcher E2E:
- Status: BLOCKED.
- Verified blocker: production candidates/guarded claim/recovery unavailable.
- Responsible owner: #354 with @Jathuja #353 and @pavi3alnx #351.
- Unblock condition: approved production adapters and real assignment/role/failure journey passes.
- Evidence: #226, #354, PR #369 and current source regression.

G02 — Dispatch Assignment:
- Remaining implementation: approved capacity concurrency/late-proof policy and production adapter, owner #354.
- Tests: assignment PostgreSQL 27/27; service 29/29; capacity race remains characterized.
- Evidence: execution attachment and matrix.

G30 — Proof Validation:
- Live verification: real S3 PostgreSQL verified; real upstream lifecycle NOT verified.
- Remaining scenarios: approved full flow and negative/retry/persistence/downstream assertions.
- Evidence: proof PostgreSQL 30/30; limitations and matrix above.

Release impact:
- Critical blockers: G36 production dispatch dependency; G31 full lifecycle; G03 progress; G02 capacity/production adapter; G30 live acceptance pending.
- Accepted risks: none recorded by this audit; no risk acceptance inferred from merged PRs or closed provider issue.
- Independent reviewer decision: pending @Niro0526 for #359; domain review remains with #354's reviewer @pavi3alnx. This audit does not grant release approval.
