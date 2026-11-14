# Candidate evidence index — Issue #359

Status: PARTIAL. Release decision: BLOCKED. Starting SHA:
`1f1b766d00f18c8eae788a1f399b34fb88e1bb0c`, verified against `origin/develop`.
Working branch: `feature/359-complete-blocker-resolution`.

## Evidence classification

- Historical: [8A focused audit](courier-dispatch-follow-up-8a.md), 389 passes;
  [PR #369](https://github.com/pirathee587/Zentro/pull/369) and its prior CI.
  These do not certify this candidate.
- Current local execution: full reactor `-DrunE2E verify` exited 1;
  [full implementation-phase attempt](evidence/359-full-attempt.json) records
  2,545 tests, 5 failures, 1 error and zero skips (2,539 passes). All 2,514 service /
  shared-security tests passed. The new fixture exposed receipt failures, an
  inherited-role setup issue and polling-triggered 429. Its corrected version is
  rerun separately at `311ac2b7f732a2f59b11e5d3060a01b4fb39e856`.
  [Focused execution results](evidence/359-current-results.json) contain safe suite counts,
  exact source/command/exit and limitations. Missing/skipped suites never imply PASS.
  The full attempt started before implementation was committed and is explicitly
  marked as such. It is not certification of the final candidate. Focused test
  source and all six JAR Build-Revision fields match `311ac2b`; subsequent changes
  are evidence/documentation only. No complete green full-candidate run is claimed.
  Focused result: **19 tests, 13 passes, 5 failures, 1 error, zero skips**, exit 1,
  finished 2026-11-14 19:18:25 +05:30. Five failures are gateway receipt checks
  (owner expects 200; other permitted roles expect missing-resource 404; all see
  403). The error is a bounded tracking wait expecting 200, observing 404.
  Corrected pure-role setup, forged/expired/wrong-audience/foreign-issuer rejection,
  six health endpoints and five quiesced database restores passed.
  Direct S2 owner receipt returned 200 and foreign merchant returned 404. Booking
  assertions verified one saved booking, one `parcel.created` outbox fact and zero
  transition-history rows after matching replay. Tracking restart assertions were
  **not reached** because initial public hydration failed; no restart success is claimed.
- CI verification: UNVERIFIED until a run for the actual PR head is available.
- Independent review: UNVERIFIED; @Niro0526 must record a decision.

## Scenario ownership and acceptance status

| Scenario | Issue / primary owner / reviewer | Status | Command/report evidence and exact remaining gap |
|---|---|---|---|
| G02 assignment/capacity | #354 @Nilakshana23 / @pavi3alnx | BLOCKED | Existing AssignmentPostgresIntegrationTest characterizes oversubscription; approved atomic capacity and real adapter absent. R01/R03. |
| G03 courier progress | #354 @Nilakshana23 / @pavi3alnx | BLOCKED | Missing approved orchestration/authority/retry/handoff; R02. |
| G30 proof | #354 @Nilakshana23 / @pavi3alnx | PARTIAL | Existing PostgreSQL proof tests; complete authenticated S1/S2/Kafka/S4 receipt journey waits on G02/G03. |
| G31 courier lifecycle | #354 @Nilakshana23 / @pavi3alnx | BLOCKED | Domain CourierLifecycleIT absent; R01/R02/R05/R06. Main 10 does not author this scenario. |
| G36 dispatcher lifecycle | #354 @Nilakshana23 / @pavi3alnx | BLOCKED | Domain DispatcherAssignmentIT absent; production candidates/claim/release/recovery unavailable. R01. |
| G28 setup/operations | #359 @pirathee587 / @Niro0526 | PARTIAL | [Runbook](../operations/backend-runbook.md), actual shared-stack execution; independent walkthrough and restore drill required. |
| G33 security | #359 @pirathee587 / @Niro0526 | PARTIAL | BackendRoleAcceptanceIT; [security matrix](../security/backend-final-signoff.md). Gateway receipt defect and expired/disabled/stale-user/all-route evidence incomplete. |
| G34 booking/history/receipt | #359 @pirathee587 / @Niro0526 | PARTIAL | BackendRoleAcceptanceIT real booking/idempotency/source recount/public projection/direct receipt. Gateway receipt and private-owner hydration remain blockers. No duplicate CREATED transition inserted. |
| G39 clean runtime | #359 @pirathee587 / @Niro0526 | PARTIAL | BackendAcceptanceStack starts actual S1–S5 and gateway, independent DBs, Keycloak/Kafka, completion client and enabled consumers. Health is separate from lifecycle/recovery acceptance. |
| G40 evidence | #359 @pirathee587 / @Niro0526 | PARTIAL | This index, safe evidence runner and current reports; full required scenario/reviewer/CI evidence still incomplete. |
| G41 candidate release | #359 @pirathee587 / @Niro0526 | BLOCKED | Critical domain gaps, approval/CI/security/recovery and release policy gates unmet. |
| G42 handover | #359 @pirathee587 / @Niro0526 | PARTIAL | [Handover package](../operations/backend-handover.md); walkthrough has not occurred. |
| X04 completion docs | #359 @pirathee587 / @Niro0526 | PARTIAL | [Backend diagrams/design/contribution appendix](../architecture/backend-completion-359.md); required deliverable/deployment/contribution sign-off UNVERIFIED. |

## Current static checks and coordination

`docker compose config --quiet`: exit 0. Python evidence runner syntax/help:
exit 0. `git diff --check`: exit 0 at preparation; repeated before commit.
`python3 docs/api/build_openapi.py --check`: exit 1, exact error:

```text
parcel: controller/spec mapping difference;
missing=[('get', '/api/v1/parcels/{id}/receipt'),
         ('get', '/api/v1/parcels/{id}/receipt/pdf')] extra=[]
```

Reproduction of the corrected acceptance run, from `backend`:

```sh
mvn --batch-mode --no-transfer-progress \
  -Dbuild.revision=311ac2b7f732a2f59b11e5d3060a01b4fb39e856 \
  -pl merchant-order-webhook-e2e -am -Dtest=BackendRoleAcceptanceIT \
  -Dsurefire.failIfNoSpecifiedTests=false -DrunE2E verify
```

This selects the shared acceptance class only; service unit suites are not rerun
by that selector. Their 2,514 current results belong to the separately described
full implementation-phase attempt. Five Python evidence-integrity tests passed
with `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s
scripts/backend-acceptance -p 'test_*.py'`. The runner returns acceptance exit 2
even after a green Maven run if required lifecycle/dispatcher suites are absent or
any suite is skipped. It never grants independent review or release approval.
Raw local logs: `/tmp/zentro-359-full.log`, `/tmp/zentro-359-focused.log`;
these are deliberately not committed. Safe JSON results preserve status/count evidence.

This is a verified Main 02 integration dependency, not something Main 10 fixes
by weakening checks. Detailed [owner requests](issue-359-owner-requests.md) were
posted with user authorization:

- [R01 source — #353](https://github.com/pirathee587/Zentro/issues/353#issuecomment-6877965033)
- [R02/R03 progress/capacity — #354](https://github.com/pirathee587/Zentro/issues/354#issuecomment-6877965270)
- [R04 gateway/contracts — #351](https://github.com/pirathee587/Zentro/issues/351#issuecomment-6877965571)
- [R07 runtime/recovery — #352](https://github.com/pirathee587/Zentro/issues/352#issuecomment-6877965829)
- [R05 private tracking hydration — #357](https://github.com/pirathee587/Zentro/issues/357#issuecomment-6878010054)
- [R05 runtime hydration evidence — #357](https://github.com/pirathee587/Zentro/issues/357#issuecomment-6878178572)
- [R06 disablement/security — #350](https://github.com/pirathee587/Zentro/issues/350#issuecomment-6878010371)

No reply/approval, owner production PR, release risk acceptance or reviewer
sign-off is inferred from these requests. R05/R06 remain explicit dependencies.
Existing user deletions of `docs/service-env-templates.html` and `.pdf` are
preserved and excluded from this PR.

## Courier / Dispatch / Dispatcher Follow-up

Supplied audit baseline:
- Newly verified tests: 299/299 passed
- Build & Packaging: PASS
- Memory crashes: 0
- Total passing results including assignment tests: 326
- Revalidated in current session: NO for that historical selection; current commands are recorded separately.

G03 — Courier Progress:
- Status: BLOCKED.
- Verified blocker: approved S3 progress/failure contract and implementation absent.
- Responsible owner: @Nilakshana23 #354 with Main 04/02.
- Unblock condition: R02 approved, implemented and real owner/state/retry/handoff acceptance passes.
- Evidence: source reconciliation, R02/R03 owner comment above.

G31 — Courier Lifecycle E2E:
- Status: BLOCKED.
- Verified blocker: unavailable production source and G03 orchestration.
- Responsible owner: @Nilakshana23 scenario; Main 10 shared support.
- Unblock condition: real booking→assignment→progress→proof→S2→Kafka/S4/receipt and recovery passes.
- Evidence: current domain source and absence of CourierLifecycleIT; R01/R02.

G36 — Dispatcher E2E:
- Status: BLOCKED.
- Verified blocker: candidate/claim/release/recovery provider and adapter absent.
- Responsible owner: Main 04/05 with Main 02 contract.
- Unblock condition: approved real production dispatch journey and failure/recovery acceptance.
- Evidence: UnavailableParcelSource, R01, domain DispatcherAssignmentIT absent.

G02 — Dispatch Assignment:
- Remaining implementation: approved atomic capacity and adapter; late-proof policy.
- Tests: current reactor results retained separately; old green race characterization does not establish enforcement.
- Evidence: R01/R03 and current execution report.

G30 — Proof Validation:
- Live verification: full cross-service journey incomplete.
- Remaining scenarios: real canonical courier, downstream lifecycle/projection/receipt, retry/recovery.
- Evidence: current S3 persistence results, domain ownership and R01/R02/R05/R06.

Release impact:
- Critical blockers: production source/capacity, progress/lifecycle, gateway receipt, private tracking and security/recovery evidence.
- Accepted risks: none approved here.
- Independent reviewer decision: UNVERIFIED, requested from @Niro0526 on PR.
