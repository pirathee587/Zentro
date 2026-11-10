# Notification and reporting projections (#195)

Reports API contract approval and implementation remain a follow-up; **this change adds no reports endpoint**. `docs/api/delivery-reports.md` is absent on the inspected develop base. Gateway `/api/v1/reports/**` routes to S4, which has no matching controller. Dashboard #74 remains blocked on a separately approved API. No customer/courier/destination breakdown can be reconstructed from fields the producers do not publish.

Base: `6666863a79dbc8549fc723d33cbf1406866e8ffd`. Implementation owner: @Deadsecnote1. Requested independent reviewer in the task: @pirathee587. That account is also the authenticated PR author. The owner selected @kirusigan03 as independent reviewer, with @Niro0526 as fallback, because GitHub does not allow authors to review their own PR. No self-approval is asserted. The issue's `DISPATCHER_ADMIN` wording maps to the existing combined dispatcher/admin role **DISPATCHER**. No role is renamed and no ADMIN role or portal is introduced.

## Findings, serious items first

| ID | Severity | Status | Evidence and result |
|---|---|---|---|
| P1 | Major | fixed | S4 accepted missing IDs/times by generating values and raced on exists-then-save; the existing PK protected committed rows, but a losing transaction could emit an uncommitted live milestone before failing. It now validates first and claims `(consumer_name,event_id)` with INSERT ON CONFLICT in the same transaction as history and projections. |
| P2 | Major | fixed | S4 updated current tracking state in arrival order. Parcel locking, business-time ordering and terminal precedence now prevent late nonterminal facts from replacing a terminal state. History retains each accepted event once. |
| P3 | Major | fixed | S4 exhausted database retries could skip a business event. Database/transaction exceptions now retry without exhaustion; offsets do not advance on a failed transaction. Invalid contracts are discarded without poison retries. A real database outage necessarily delays that partition until recovery. |
| P4 | Major | fixed | S5 overwrote per-delivery retry summaries and persisted provider exception text. Append-only per-attempt history is captured atomically, and notification summary errors use a fixed provider-failure category. |
| P5 | Major | follow-up | External sending and database commit cannot be atomic. A process crash after receiver acceptance but before commit can resend. Database deduplication is not exactly-once email/SMS/HTTP delivery; receivers/providers need event-ID idempotency. |
| P6 | Major | follow-up | No approved reports API exists; courier assignment events/identity and destination fields are unavailable. No endpoint, cross-service read, synchronous backfill or fabricated identity record is added. |
| P10 | Major | needs decision | Legacy S4 history may contain old generated IDs or fallback timestamps/statuses. V3 recounts retained S4 state; it cannot prove that historical state matches the original Kafka facts without a provenance audit or retained-topic replay. |
| P7 | Minor | fixed | Unknown version headers, oversized messages, wrong types/missing required fields and fields outside the approved envelope are rejected before durable webhook payload storage. Logs use categories, not payload text. |
| P8 | Minor | needs decision | Retention is not approved. No purge job is added. Historical overwritten S5 attempts cannot be reconstructed and are not invented by the migration. |
| P9 | Minor | follow-up | Production volumes, long-running outage/soak and operator rebuild automation remain unverified. Database transaction tests and a bounded listener restart cover local behavior, not a production Kafka cluster. |

## Event inventory and projection map

Inventory is from S2's `ParcelBookingService`, `MerchantOrderService`, `ParcelStatusService`, `OutboxPublisher` and the actual consumers. Schemas are under `contracts/events/`. Related #58, #24, #64, #32, #136 and #134 are closed; webhook PR #114 is merged. #74 is open, assigned to @Deadsecnote1.

| Event / key | Available data | Producer guarantee |
|---|---|---|
| `parcel.created` / parcel UUID | Envelope eventId, eventType, aggregateId, occurredAt; payload trackingRef, CREATED, source, occurredAt, optional merchantId | S2 business insert and outbox insert commit together; publisher waits for Kafka send, then marks published. A crash between send and marking may duplicate the event. |
| `parcel.status.updated` / parcel UUID | Same envelope; payload trackingRef, previousStatus, status, actorRole, occurredAt, failureReason only for FAILED, optional merchantId | S2 status/history/outbox commit together; same at-least-once publisher. S3's accepted delivery changes reach S2 through its existing contract. |
| `delivery.assigned`, `delivery.failed` | Schema/architecture artifacts exist, but no S3 publisher was found | Not consumed or invented by this change. FAILED counts use actual S2 status events. |

There is **no schemaVersion JSON property**. S2 adds Kafka headers eventId, eventType and **schemaVersion=1**. Consumers accept header 1 or a missing legacy header and safely reject an unsupported header version. A JSON schemaVersion property is outside the existing envelope and is rejected. The JSON event ID is mandatory and authoritative; no ID is generated from an offset, timestamp or fallback header. Event time and payload event time must agree. No customer subject, courier ID, delivery assignment time, recipient identity, destination type or geography is available. Optional merchantId identifies a merchant account; it is not customer/courier identity. No new projection stores it.

| Consumer | Owning tables | Idempotency key and transaction | Ordering |
|---|---|---|---|
| S4 `TrackingKafkaConsumer` | tracking.processed_event, tracking_event, delivery_projection, delivery_daily; delivery_daily_metrics view; existing tracking_parcel only if present | Existing marker PK `(consumer_name,event_id)`, claimed before effect; history flush and projection update share the JPA/JDBC database transaction | Lock parcel before history insertion; terminal rule below; lock contribution days chronologically |
| S5 `ParcelEventConsumer` | notification.processed_event, notification_attempt, delivery_attempt_history | Existing PK `(consumer,event_id)` and notification `(event_id,channel)` unique key; logical decision commits before acknowledgment/sending | Existing notification delivery row lock; retry scheduler and first attempt share the same dispatcher |
| S5 `MerchantWebhookConsumer` | notification.processed_event, webhook_delivery, webhook_subscription, delivery_attempt_history | Existing marker per consumer and `(subscription_id,event_id)` delivery uniqueness; decision commits before acknowledgment/sending | Existing SKIP LOCKED attempt claim and per-parcel open-delivery holdback retained |

S4 explicitly disables auto-commit. Its listener returns only after its transaction commits; container offset commits follow successful handling. Database errors escape to the existing safe error handler for retry, rather than being swallowed or acknowledged. S5 retains manual acknowledgment after its storage transaction. Malformed S5 messages are recorded by bounded reason/position, then acknowledged; failure to store that rejection is retried. No Kafka DLT is introduced. S4 skips invalid records with a fixed category and continues to the next record. The message bound is 65,536 Java characters before JSON parsing; field limits follow the approved shape. Valid failureReason remains contract-permitted history data; it is excluded from counts and new attempt history.

## Ordering and metric definitions

Timezone is **Asia/Colombo**, defined once in `tracking.reporting_day(timestamptz)`. All stored times are timestamptz/Instant; reporting does not depend on JVM or database session timezone. Colombo midnight is 18:30 UTC on the previous date.

S2's `ParcelLifecycle` makes DELIVERED and FAILED terminal, with no failed-to-redelivered transition. For a parcel's complete observed history:

- Creation is the earliest CREATED fact by occurredAt.
- Terminal is the earliest DELIVERED/FAILED fact by `(occurred_at,event_id)` ascending. Conflicting later terminal facts remain in history but add no second terminal count. A late earlier terminal fact moves the old contribution to its correct date/status; it does not add an extra count. This is a deterministic anomaly rule, not support for an unpublished redelivery lifecycle.
- Current state is that terminal status if one exists; otherwise the latest nonterminal fact by `(occurred_at,event_id)` descending. Older nonterminal facts never replace newer current state.
- A terminal event arriving before its creation event can be counted as terminal. It does not fabricate a creation; active only includes parcels with an observed creation.
- Every accepted event is retained once in tracking_event. Existing identity-bearing tracking_parcel rows are updated if present; absent rows are not fabricated from missing personal/ownership fields. Live milestones are emitted after transaction commit, so a rollback does not leak an uncommitted milestone.

| Metric | Definition / numerator | Denominator | Day and edge cases |
|---|---|---|---|
| created | Distinct parcels' earliest observed CREATED facts | None | Creation event day, even if delivery is a later day |
| delivered | Distinct parcels whose chosen terminal fact is DELIVERED | None | Terminal event day |
| failed | Distinct parcels whose chosen terminal fact is FAILED | None | Terminal event day; no count per retry or reason text |
| active | Known-created parcels whose terminal day is absent or later than the reported day | None | Snapshot at local day end; not “events received that day” |
| success_rate | Delivered on the day | Delivered + failed on the day | Numeric fraction, NULL for zero denominator; no NaN or implicit zero |

`delivery_daily` stores dates/counts only. `delivery_projection` stores parcel UUID and event-derived state/timestamps, with no identities or addresses. `delivery_daily_metrics` fills zero-activity intervening dates from the earliest through latest observed event day, calculates active, and exposes the nullable rate. It does not manufacture dates beyond the observed interval or an API data-as-of field. A future approved reports API must define range limits, empty ranges and data-as-of behavior explicitly.

The incremental writer recomputes only the changed parcel from S4 history, then subtracts its previous creation/terminal contributions and adds its new ones. Parcel locks serialize different events for one parcel; ordered day updates prevent lost increments across parcels and avoid day-lock inversion. Duplicate claims return before any effect. PostgreSQL locks/constraints, not an application-only exists check, decide concurrent winners. History, marker, state and counts roll back together.

## Independent recount and replay

The [plain SQL recount](projection-evidence/delivery-recount.sql) reads **only tracking.tracking_event** and the shared timezone function. It does not read the incremental state or daily table. It independently selects first creation/terminal facts, generates dates, counts active, and computes the nullable rate. Run it read-only against S4's database with an approved local database client, then compare its ordered rows to:

```sql
SELECT * FROM tracking.delivery_daily_metrics ORDER BY day;
```

The same SQL is executed by `DeliveryProjectionIntegrationTest`; its hand-calculated fixture includes Colombo midnight, cross-day creation/delivery, failure-only and no-terminal days, empty intervening days, terminal-before-nonterminal arrival, conflicting terminals, equal-time UUID ties, duplicate and reversed replay. Sequential/concurrent duplicates, different parcels racing on the same day and rollback after aggregate update are separate checks.

Replay of the topic into an **empty disposable S4 database** with a new consumer group yields the same event-supported projections while the required topic history remains retained. Replaying into an existing database is a no-op for processed IDs. Do not clear processed markers alone: that is not a rebuild procedure. V3 backfills from S4's existing history and leaves identity rows and event history intact; legacy history with null event/parcel IDs is excluded because it cannot be associated safely. Old generated random IDs cannot be proven to match original producer IDs. The previous consumer could also use fallback timestamps and status defaults; V3 does not reconstruct or certify their original business-time accuracy. Backfill equality is equality with retained S4 history, not proof of historical Kafka provenance. Historical reporting acceptance needs a data-quality audit or an approved replay from complete retained topics. Durable-history rebuild automation, cutover, backup verification and incomplete-topic handling remain a separate operator follow-up; this PR provides recount verification rather than a destructive rebuild endpoint.

## S5 attempt history and retry policy

Existing `notification_attempt` is the logical notification **summary**, despite its name; `webhook_delivery` is the logical webhook summary. Existing dispatcher endpoints continue returning these summaries; the merchant endpoint continues returning only the merchant's own deliveries. The append-only table is internal, with no new endpoint or response fields.

`notification.delivery_attempt_history` captures each new committed attempt with delivery kind, delivery UUID, event UUID, lifetime attempt number, policy attempt number, outcome, HTTP status/class or bounded DELIVERY_ERROR category, transaction-start timestamp, completion timestamp, next retry time and terminal flag. No secret, signature, Authorization header, URL, merchant response body, rendered message or free-form exception is stored there. Existing rendered notification text and exact webhook envelope remain in their existing logical delivery tables only, under their existing contracts.

Database AFTER UPDATE triggers write history when the summary attempt counter increments. The summary row lock serializes attempts, and the primary key `(delivery_kind,delivery_id,attempt_number)` protects each history position. History UPDATE/DELETE is rejected. Rollback removes both the summary change and history. Started time is the database transaction start (including lock/setup time), not a claimed exact socket-start time; finished time is the capture's database wall clock. The table has no historical backfill because overwritten attempts cannot be recovered honestly.

The documented manual webhook replay resets the policy retry counter. A separate lifetime sequence preserves all earlier attempts; replay adds lifetime attempt N+1 with policy attempt 1. A historical terminal attempt remains terminal for that completed retry cycle. No automated replay or purge is added. Disabled/held-back deliveries that make no attempt do not fabricate attempt records.

Webhook retry policy is unchanged: 408, 429, 5xx, timeout, connection/network and DNS failures retry with capped exponential backoff, default maximum **8** attempts. Other 4xx and un-followed 1xx/3xx fail immediately. Notification retry default remains **5**. Terminal exhaustion is a FAILED outcome with terminal=true, final policy attempt number and no next retry. Tests reuse the local HTTP receiver for 500→200, 429→200, timeout and permanent 400, plus existing retry-policy and exhaustion tests. HTTP response bodies are discarded by the existing sender.

Growth: one processed marker and logical notification decision per valid event; zero attempt-history rows for SKIPPED, otherwise one per committed send attempt (default up to 5 per cycle). The webhook consumer has its own marker, zero deliveries for no merchant/subscription, otherwise one logical delivery per subscription/event and up to 8 attempt-history rows per cycle. Manual replay can grow history beyond those per-cycle bounds. Malformed rejection rows are per handling attempt and have no business effect. Retention and archive/purge policy need approval.

Existing indexes remain: processed marker PKs, notification event/channel uniqueness, notification due/status+time and tracking-reference/time indexes, webhook subscription/event uniqueness, due/status+time and subscription/creation indexes, S4 parcel/time and tracking-reference/time history indexes. The new attempt-history PK supports delivery-kind/delivery-ID/attempt ordering. No speculative secondary index is added. Existing global history sorting, future event-ID/history searches and large date-range queries need workload measurement before additional indexes. The Testcontainers plan fixtures record 10,000-row EXPLAIN evidence for S4 parcel history and S5 per-delivery attempt history.

## Migrations and boundaries

- S4 V3 `event_driven_delivery_projections`: new owned state/count tables, timezone function, metrics view and backfill from S4 history.
- S5 V4 `append_only_delivery_attempt_history`: internal history and capture/immutability triggers; existing summary rows remain intact.
- Applied V1/V2/V3 files are unchanged. Empty databases are exercised by service integration tests; prior-version populated databases are exercised by dedicated Flyway tests. No matching new migration version was found in fetched remote branch trees; GitHub listed no open PR at inspection time. This is a point-in-time check, not a reservation against later work.
- No S2/S3 query, cross-service write, synchronous backfill, new dependency, API/event schema change or role rename is introduced. Strict rejection now enforces fields already required/allowed by the existing schemas.

## Follow-ups and decisions

| Item | Owner evidence | Next action |
|---|---|---|
| Reports API contract and dashboard #74 | @Deadsecnote1 (#74 and #195 primary owner); backend maintainer @pirathee587 (history/CODEOWNERS) | Approve metrics/range/as-of/security contract, then implement and test the dispatcher endpoint separately |
| Assignment/courier/destination reporting | Producer contract owner to be assigned; @14Jannan owns related #58 | Approve required event fields/publication before any downstream projection; no new numbered dependency is invented |
| Receiver/provider idempotency across send/commit crash | @pirathee587 (backend history/CODEOWNERS), integration provider owner to be assigned | Verify event-ID deduplication and recovery with actual receivers/providers |
| Legacy history provenance | @pirathee587 (backend history/CODEOWNERS), @Deadsecnote1 (#195 owner) | Audit historical fallback values or approve retained-topic replay before accepting historical reports |
| Retention/archive and production growth | @pirathee587 (backend history/CODEOWNERS), operations owner to be assigned | Measure retained sizes and approve policy before a purge or index change |
| Durable-history rebuild/cutover and long soak | @pirathee587 (backend history/CODEOWNERS), operations owner to be assigned | Design maintenance cutover and verify retained-topic/history completeness |
| Independent reviewer | @kirusigan03 (owner-selected replacement), fallback @Niro0526 | Independent sign-off pending; primary assignee stays unchanged |

Not verified: deployed gateway-to-S4/S5 event-to-response end-to-end, deployed/shared Keycloak, production Kafka volumes, a multi-broker failover/long soak, provider exactly-once behavior, production migrations, reports endpoint or reports dashboard UI. Listener stop/start is a bounded local reassignment check, not a multi-instance cluster rebalance proof. Kafka startup failures are not counted as successful tests.

## Acceptance evidence

| #195 criterion | Proof / status |
|---|---|
| Inspect implementation, WARP, contracts and related issues | Event inventory and findings above; base recorded |
| Scope/reuse | Existing consumers, marker constraints, summary tables, retry policy and test receiver retained |
| Four-role model | Existing CUSTOMER/MERCHANT/COURIER/DISPATCHER names retained; DISPATCHER_ADMIN wording documented |
| Five-service/database ownership | Only S4/S5 own-schema migrations and event-derived writes; no synchronous backfill |
| Task-specific negative/security tests | Consumer validation, duplicate/concurrent/rollback/replay/recount, append-only attempt checks; existing role/merchant-isolation suites reused |
| Run builds/tests | 2157 tests passed in the final full reactor; per-module counts and actual output below |
| Focused PR to develop | Draft with Refs #195; reports endpoint explicitly excluded pending contract |
| Independent review | Pending @kirusigan03 sign-off |
| Merged commit SHA | to be recorded after merge |

## Unapproved reports API sketch — no endpoint implemented

A possible contract for separate approval is `GET /api/v1/reports/deliveries?from=YYYY-MM-DD&to=YYYY-MM-DD`, restricted to DISPATCHER at both gateway and S4. Both dates would be required, inclusive, with a proposed maximum of 31 calendar days and `from <= to`. Proposed response fields are `from`, `to`, `timezone`, `dataAsOf` and `days`, where each day has `day`, `created`, `delivered`, `failed`, `active` and nullable fractional `successRate`. A zero-activity day would have zero counts and NULL rate. No parcel IDs, identities, reasons or destination data would be returned.

The path, 31-day limit, date defaults/empty ranges and a consistent projection-ingestion watermark for `dataAsOf` all require approval; maximum event occurredAt is not a reliable freshness watermark for late arrivals. Future tests would cover 200/401/403, every non-dispatcher role, invalid/missing/reversed/oversized ranges with problem+json fieldErrors, inclusive midnight boundaries, empty ranges, and equality with the independent recount. No OpenAPI or existing API contract is changed by this sketch.

## New and reused tests

S4 adds six PostgreSQL projection cases (hand-counted dates/reverse replay, concurrent duplicate claims with rollback, earlier terminal correction, equal-time terminal ties, concurrent parcels on one day, legacy null parcel association), one populated Flyway migration case and one Kafka listener stop/start case. Existing consumer unit/logging cases now enforce rejection instead of inventing missing IDs or times; existing Kafka poison/rollback and authorization suites are reused. The real Kafka success test carries the actual producer schema header.

S5 adds concurrent claim/rollback/immutability verification, 429→200 history, all eight webhook attempts at exhaustion, populated Flyway migration, unsupported version/oversize and unknown/missing/ambiguous JSON cases, plus header-version acknowledgment verification. Existing 500→200, timeout, permanent 400, notification exhaustion, operational replay, dispatcher-role and merchant-ownership suites gain history assertions where applicable. No new test framework or dependency is introduced.

## Actual final verification results

Final tested source: `52821547576b88aef401fcda2cb5777c86c19115`, based on develop `6666863a79dbc8549fc723d33cbf1406866e8ffd`. From `backend/`, **`mvn --batch-mode test package` passed in 9m29s**, finishing `2026-11-10T17:31:44+05:30`. All executed Testcontainers cases ran. [Actual XML totals](projection-evidence/test-counts.txt), [reactor output](projection-evidence/reactor-result.txt) and [commands/attempts](projection-evidence/verification-attempts.txt) are retained.

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| Security starter | 62 | 0 | 0 | 0 |
| Gateway | 210 | 0 | 0 | 0 |
| S1 identity/partner | 420 | 0 | 0 | 0 |
| S2 parcel/pricing | 675 | 0 | 0 | 0 |
| S3 dispatch/courier | 259 | 0 | 0 | 0 |
| S4 tracking/reporting | 99 | 0 | 0 | 0 |
| S5 notification/integration | 432 | 0 | 0 | 0 |
| **Total executed** | **2157** | **0** | **0** | **0** |

The merchant order/webhook E2E module packaged successfully but its separate tests were **NOT RUN** because the default profile skips them; `-DrunE2E` was not selected. The opt-in performance profile was **NOT RUN**. These excluded groups are not counted as executed tests with zero skips. No frontend/Node tooling was run. Existing disposable Keycloak/gateway integration cases ran as part of their modules; this does not verify a deployed/shared realm or a full S4/S5 deployment.

An earlier full attempt failed two S5 rejection-category assertions; the category order was fixed. Another full attempt hit an unchanged S2 Kafka container startup failure (`/testcontainers_start.sh: Text file busy`, exit 126), before later modules ran. The final confirmation passed all modules; failed/interrupted attempts are preserved as such, not hidden as successes. Container-startup stability remains a test-infrastructure follow-up for @pirathee587 (backend history/CODEOWNERS); the next action is to reproduce and isolate the Docker/Testcontainers startup race separately from this PR. No S2 source change was made. No containers remained running after the final run.

The 10,002-row S4 history fixture used `idx_tracking_event_parcel_time` with a two-row result and small tie sort. The 10,001-row S5 attempt fixture used `delivery_attempt_history_pkey` for one delivery's ordered history. [Actual EXPLAIN ANALYZE/BUFFERS output](projection-evidence/explain-plans.txt) records measured times and buffers; these are query-plan checks, not production performance claims. No additional secondary index was added.

The recorded commands were `java -version`, `mvn -v`, `/snap/docker/current/bin/docker --version` and `uname -sr`. The environment output below is also retained [as text](projection-evidence/environment.txt). Docker used the already-installed `/snap/docker/current/bin/docker` CLI; tests connected through `/var/run/docker.sock`.

```text
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment (build 21.0.12.1+1-1-24.04.4-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.12.1+1-1-24.04.4-Ubuntu, mixed mode, sharing)
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.12.1, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en, platform encoding: UTF-8
OS name: "linux", version: "7.0.0-42-generic", arch: "amd64", family: "unix"
Docker version 29.8.0, build 88096ef
Linux 7.0.0-42-generic

```
