# Issue #356 — S5 reliability implementation and verification

## Baseline and scope

- Starting branch: `develop`, `b7fc2f72bc63406940cf8bf6bc2b1bdbc317ab22`.
- `git fetch origin --prune` succeeded. A separate clean worktree at
  `/home/piratheepan/Desktop/Zentro-356` pulled with `git pull --ff-only origin develop`;
  HEAD and origin/develop both matched the SHA above. WARP.md was read from that baseline.
- Feature branch: `feature/356-kafka-notifications-and-operational-audit`.
- Original worktree's user deletions of `docs/service-env-templates.html` and `.pdf`
  were preserved. This branch includes no frontend changes or other service changes.

## Requirements and decisions

| Requirement | Status | Evidence / remaining decision |
|---|---|---|
| G15 audit API/storage | BLOCKED / NOT IMPLEMENTED | Gateway already maps `/api/v1/admin/audit-logs/**`; S5 has no general handler/migration. Audit sources, pagination, retention, redaction, permissions and ownership still require approval. Route remains reserved in documentation; no invented handler or storage policy. |
| G16 courier notices | BLOCKED / NOT IMPLEMENTED | S3 lifecycle events exist; S5 subscribes to parcel topics only. No approved S1 contact API, templates/channel/consent/replay or release-scope decision. |
| G17 recovery | PARTIAL | Separates successful-send persistence errors from provider errors; adds disposable outage/replay and rollback characterization. See execution evidence below and remaining limits. |
| X06 real provider readiness | BLOCKED / NOT IMPLEMENTED | Existing mock-only D1 implementation is not acceptance of mock-only release scope and is not external SMS/email. Product/security release decision is outstanding. |

The user confirmed during implementation on 2026-11-13 that no approved G15/G16/X06
policy document was found and explicitly directed G17 to proceed independently while
leaving those expansions blocked. This records the blocker, not policy approval.

Reviewed [#356](https://github.com/pirathee587/Zentro/issues/356),
[#348](https://github.com/pirathee587/Zentro/issues/348),
[#349](https://github.com/pirathee587/Zentro/issues/349) and
[#24](https://github.com/pirathee587/Zentro/issues/24).
The [existing mock decision](https://github.com/pirathee587/Zentro/issues/24#issuecomment-6640125215)
approves tracking-reference mock recipients and selected statuses; it does not approve
real contact lookup. Main 01/02/03/05 interface/runtime work remains a gate for its named
expansions only. No DLT or finite ingestion retry exhaustion is introduced.

## VERIFIED EXISTING — source facts revalidated

- `NotificationService` atomically claims `(consumer,eventId)` and inserts an outcome;
  `ParcelEventConsumer` acknowledges after that transaction and then invokes delivery.
- `KafkaConsumerConfig` uses unlimited five-second error retry. Rejection persistence
  precedes acknowledgement. Rejected rows contain position/reason, not recoverable payload.
- `RecipientResolver` resolves tracking references and `ChannelPreferenceService` supports
  MOCK_SMS only; no courier assignment consumer or contact provider was added.
- Migrations V1–V5 provide notification/webhook storage, propagation, append-only attempt
  history and one-time webhook replay evidence; applied migrations are unchanged.
- S1 user audit, S3 assignment audit and S5 webhook replay facts remain separate. No
  approved ingestion event/API exports them into a general S5 audit stream. Runtime
  cross-service SQL and speculative aggregation were not introduced.
- Existing Dispatcher history/replay routes and merchant webhook API contracts remain
  unchanged; general audit policy tests are blocked with G15, not represented by history tests.

## Change and independent expectations

`NotificationDispatcher` previously caught database/metrics failures from the success path
inside its provider catch, attempting to classify them as provider rejection. A successful
provider call now leaves that catch before persisting SENT; persistence failure escapes and
rolls back. Provider exception handling and the existing provider retry budget are preserved.

New regression expectations are based on fixture event IDs and transaction boundaries:

- PostgreSQL stopped after a warmup: listener reports a failed delivery, Kafka committed
  offset cannot pass the unpersisted event, and restarting the same database yields one
  SENT logical row and advances the offset.
- Listener stopped before publish: broker acknowledges an event; broker and listener
  restart deliver it; replaying its stable ID followed by a same-partition marker leaves
  exactly one logical notification and one committed send attempt.
- Result-write failure after mock acceptance: transaction rolls back, PENDING/zero attempts
  and no attempt history remain; retry calls the mock sender a second time but commits
  one SENT outcome. This explicitly demonstrates at-least-once sends, not exactly-once.
- Separate JVM process kill/restart: one worker drains a committed pending row, is forcibly
  terminated, and a new worker PID drains work committed while the first was down. Both
  use production application startup and the actual mock sender/scheduler; no real delivery
  or authenticated HTTP behavior is claimed.
- Precommit rollback: no notification or sender call; retry ingests successfully and a
  newly constructed dispatcher processes the durable pending row under a new transaction.

Existing poison-message, concurrent claims, duplicate/replay, append-only history, provider
retry/exhaustion, security and webhook tests are retained in the service verification run.
The background notification scheduler is mocked in the new recovery class to make injected
crash windows deterministic; dispatcher work is invoked explicitly. JWT decoding is mocked
in the integration base. The sender is MOCK_SMS and is spied for
call counts; repository spies inject one synthetic write failure but otherwise use real
PostgreSQL. No real email/SMS provider or merchant receiver idempotency is proven.

- Acknowledgement failure after ingestion leaves PENDING work; direct consumer redelivery
  deduplicates it and explicit dispatcher recovery completes one attempt.
- Distinct reordered DELIVERED then PICKED_UP records are both delivered; this characterizes
  current behavior without inventing a stale-event suppression rule.

## Execution evidence

Environment: Asia/Colombo, 2026-11-13; Ubuntu/Linux 7.0.0-42-generic amd64,
OpenJDK 21.0.12.1, Maven 3.8.7, Docker server 29.8.0/API 1.56, Testcontainers 1.21.4 for S5
(1.20.4 in the full-reactor gateway suite; 1.19.8 in cross-service E2E),
PostgreSQL `postgres:16-alpine`, Kafka `confluentinc/cp-kafka:7.6.1` (baseline embedded ZooKeeper; final restart fixture KRaft).
No historical totals are reused.

Commands run from `backend`:

```sh
# Baseline at b7fc2f72bc63406940cf8bf6bc2b1bdbc317ab22
mvn --batch-mode --no-transfer-progress -pl notification-integration-service -am verify
# Focused worktree iterations based on the same baseline
mvn --batch-mode --no-transfer-progress -pl notification-integration-service -am -Dtest=NotificationDispatcherTest,NotificationRecoveryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false verify
```

| Run | Exit | Tests | Failures | Errors | Skips | Explanation |
|---|---:|---:|---:|---:|---:|---|
| Baseline service reactor | 0 | 560 | 0 | 0 | 0 | Security starter 62; S5 498. Disposable Docker integrations ran. |
| Focused compile iteration | 1 | 0 | — | — | — | New test methods needed checked sender exception declarations; corrected. |
| Focused first execution | 1 | 15 | 1 | 2 | 0 | Scheduler race, restart readiness and repeated start cleanup issues. |
| Focused second execution | 1 | 17 | 1 | 2 | 0 | New offset/reordering cases included; readiness fix was made after this compilation. |
| Focused third execution | 1 | 17 | 1 | 2 | 0 | Docker remapped implicit ports after restart, preventing reconnect; fixtures now explicitly bind temporary ports. |
| Fixed-port startup iteration | 1 | 12 | 0 | 1 | 0 | Kafka fixture needed bindings for every exposed port; corrected. |
| Fixed-port repeated iteration | 1 | 12 | 0 | 1 | 0 | A local edit used the wrong working-directory-relative path; the unchanged fixture failed again. |
| ZooKeeper restart iteration | 1 | 17 | 0 | 1 | 0 | Embedded ZooKeeper retained a broker session and Kafka exited with NodeExists after restart; final fixture uses KRaft. |
| Corrected focused run | 0 | 18 | 0 | 0 | 0 | Dispatcher unit tests 11; real-container recovery tests 7, including separate JVM kill/restart. |

All focused iterations were uncommitted worktree revisions, not immutable candidate builds.
The new class mocks the scheduler to avoid racing synthetic crash windows. Existing retry
integration tests continue to exercise the actual scheduler. Test fixture endpoint binding
uses a free ephemeral host port, which Docker retains across a restart. A competing process
can claim that port before Docker binds it; container startup then fails rather than connecting
to an unrelated service. No shared/deployed container is restarted by these tests.

Implementation candidate: `2c86992b16521669778994640c6797539f5ff6bf`.
The passing focused run immediately preceded this commit and exercised identical runtime/test
sources. Documentation was finalized separately. Candidate S5 verification passed with exit 0: 568 tests, zero failures/errors/skips
(security starter 62, S5 506), using the exact service reactor command above.

Full candidate command (exit 0, completed 2026-11-13 23:55 Asia/Colombo in 14:18):

```sh
mvn --batch-mode --no-transfer-progress -Dbuild.revision=2c86992b16521669778994640c6797539f5ff6bf -DrunE2E verify
```

Full reactor: **2,424 tests, 0 failures, 0 errors, 0 skips**.

| Module | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| zentro-security-starter | 62 | 0 | 0 | 0 |
| api-gateway | 213 | 0 | 0 | 0 |
| identity-partner-service | 434 | 0 | 0 | 0 |
| parcel-pricing-service | 719 | 0 | 0 | 0 |
| dispatch-courier-service | 327 | 0 | 0 | 0 |
| tracking-reporting-service | 151 | 0 | 0 | 0 |
| notification-integration-service | 506 | 0 | 0 | 0 |
| merchant-order-webhook-e2e | 12 | 0 | 0 | 0 |

E2E classes: MerchantOrderAcceptanceIT 1, MerchantWebhookReplayIT 4,
MerchantCredentialIT 5, MerchantApprovalGapIT 2. These launch separate service processes
against disposable PostgreSQL/Kafka/Keycloak and a local mock webhook receiver. E2E uses
Kafka `confluentinc/cp-kafka:7.8.0` and Keycloak `quay.io/keycloak/keycloak:26.3.3`;
S5 recovery fixtures use Kafka 7.6.1 KRaft. Neither proves real SMS/email delivery.
PerformanceSmokeIT is intentionally excluded by the existing `runE2E` profile; the opt-in
`perf` profile was not run. No selected tests were skipped. Full-service E2E does not
expand the blocked audit/courier/provider contracts.

No OpenAPI contract changed, so OpenAPI regeneration/validation is not required for this diff.
`git diff --check` passed (exit 0).

Additional observation: the existing Prometheus registry emits a counter/gauge naming-collision
warning for `zentro.notifications.failed` versus `zentro.notifications.failed.total`. This
change documents the monitoring limitation; it does not silently rename a metric contract
or modify package 03 observability configuration.

## Operational documentation and remaining work

[Recovery runbook](../operations/s5-notification-recovery.md) covers rejection versus DLT,
existing replay authorization, outage triage, receiver deduplication and duplicate-send limits.
No collector/dashboard/alert-rule configuration was changed.

Still required for full issue completion: approved G15/G16/X06 decisions and their gated
runtime implementations/tests, external
receiver acceptance/crash evidence with real HTTP receiver deduplication, independent
@Kiruthiyan approval, passing required CI, merge and merged SHA.
Do not mark #356 Done or auto-close it from this partial PR.

## Files and publication

Modified:

- `backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/delivery/service/NotificationDispatcher.java`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/delivery/service/NotificationDispatcherTest.java`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/integration/AbstractNotificationIntegrationTest.java`

Created:

- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/integration/NotificationRecoveryIntegrationTest.java`
- `docs/operations/s5-notification-recovery.md`
- `docs/testing/issue-356-implementation.md`

Deleted: none. Runtime/test implementation commit: `2c86992b16521669778994640c6797539f5ff6bf`.
Publication status is reported in the PR description and final handover; this document
records local candidate evidence before publication. No independent approval or merge
is claimed. The overall issue remains PARTIAL/BLOCKED; passing local verification does
not satisfy the blocked policy requirements.

## Follow-up inspection and evidence (2026-11-14)

The [master workstream report](notification-master-baseline.md) records revalidated PR #371
status, two additional recovery tests and replay authorization coverage, new candidate runs,
three separately reviewable policy/contract proposal PRs, and current blockers. Original
2,424-test evidence above remains tied to its original revision; it is not a new run.
