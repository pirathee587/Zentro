# ZD-062 transactional outbox atomicity audit (#137)

Owner: @14Jannan. Independent reviewer: @hephzibah-peries.
Scope: write-side atomicity (business change + outbox row) and restart recovery of committed rows. Publish-after-commit under broker outage, ordering and "no lost events" are #196. Schema compatibility is #138; consumer idempotency and DLT handling is #139.
Code audited: `develop` at 34bd070. No production code was changed.

## 1. Result

**No atomicity defects found.** In every event-producing write, the business change and its outbox row are committed in one PostgreSQL transaction or not at all. This was verified by code review (section 3) and by tests that inject real database failures before, during and at commit (section 5). Committed events survive a restart and are published afterwards; an interrupted publish is resent with the same `eventId` (at-least-once), never lost.

## 2. Event producer inventory (all five services)

Searched in every module's main code for outbox tables, `KafkaTemplate`, `StreamBridge`, `ApplicationEventPublisher`, `@TransactionalEventListener`, `KafkaProducer`/`ProducerRecord` and direct sends.

| Service | Produces events? | How |
|---|---|---|
| S1 Identity & Partner | no | — |
| S2 Parcel & Pricing | **yes**: `parcel.created`, `parcel.status.updated` | Transactional outbox `parcel.outbox_event` (V5, V7) + `OutboxPublisher` (the only `KafkaTemplate` use in the system) |
| S3 Dispatch & Courier | no | Assignments are stored in `dispatch.delivery_assignment` without an event; status changes go to S2's API (`ParcelStatusClient`) and S2 writes the event |
| S4 Tracking & Reporting | no (consumer only) | — |
| S5 Notification & Integration | no Kafka events (consumer; sends HTTP webhooks) | — |

`delivery.assigned` exists only as `contracts/events/delivery-assigned.schema.json` and in the README topic list; nothing produces it (finding F1).

## 3. Atomicity checklist per writer (code review)

| Check | Customer booking `ParcelBookingService` | Merchant order `MerchantOrderService` | Status change `ParcelStatusService` |
|---|---|---|---|
| a. Business write and outbox row in one transaction, one DataSource | `transactions.execute(...)` wraps `repository.insert` + `writeCreatedEvent` (lines 117-119). `ParcelBookingRepository.insert` is `@Transactional` (REQUIRED, joins). `insertOutboxEvent` is `MANDATORY`. | Same pattern (lines 114-116; `MerchantOrderRepository.insert` REQUIRED) | `@Transactional updateStatus` (line 64): row lock, update, history and outbox insert. `ParcelStatusRepository` is `@Transactional(MANDATORY)` (line 20), so it can never run outside a transaction. |
| b. Transaction actually applied | `TransactionTemplate` (programmatic, no proxy needed) | `TransactionTemplate` | Called from `ParcelStatusController` on the Spring bean (proxy); no self-invocation |
| c. Kafka send before commit | none | none | none; only `OutboxPublisher` sends, after reading committed rows |
| d. Replays and rejections write no outbox row | Idempotent replay and pricing run **before** the transaction (lines 105, 110) | Replay before the transaction (line 104) | Validation, the "same status" no-op (line 92) and illegal or terminal transitions return or throw before any write |
| e. Unique event id, no personal data | `UUID.randomUUID()`; `event_id` is the primary key (V5); payload checked by `ParcelEventContractTest` | same | same |
| f. Rollback behaviour | Only unchecked exceptions; `JsonProcessingException` wrapped in `IllegalStateException` (lines 163-164); nothing swallowed inside the transaction; `DuplicateKeyException` is caught **outside** it (line 124) and retried in a new transaction | same (line 122) | `JsonProcessingException` → `IllegalStateException` (lines 150-151); optimistic lock failure → rollback (409) |
| No `REQUIRES_NEW`, `NOT_SUPPORTED`, `@Async`, threads or `@TransactionalEventListener` in S2 main code | yes (none) | | |

**Publisher (relevant to restarts):**
- One transaction per batch (`publishPending` → `publishBatch`).
- `fetchBatch` uses `FOR UPDATE OF o SKIP LOCKED` and holds back later events of a parcel while an earlier one is unpublished.
- `markPublished` runs only after `kafka.send(...).get(timeout)` succeeds.
- **If the process stops after the broker confirmed but before the batch transaction commits:** the mark is rolled back, the row lock is released, and the next poll resends the same `eventId`. Duplicates are possible and documented; consumers de-duplicate on `eventId` (S4, S5). Events are never lost.

## 4. Coverage before this audit

| Scenario | Booking | Merchant order | Status change |
|---|---|---|---|
| Outbox insert fails → nothing committed | unit test only (mocked) | none | `ParcelStatusIntegrationTest.outboxFailureRollsBack` |
| Failure after both writes (at commit) | none | none | none |
| Other write in the transaction fails | none | none | none (history insert) |
| Rejected request → no outbox row | `BookingPricingOutageIntegrationTest` | parcels only | `ParcelStatusIntegrationTest` (repeat, invalid, terminal) |
| Replay → no second row | `BookingDuplicateSubmissionIntegrationTest` | `MerchantOrderIntegrationTest` | repeat test |
| Concurrent writers | 5 parallel, same key | none | identical updates only |
| Restart with committed, unpublished rows | none (`OutboxKafkaIntegrationTest` covers a broker outage in one running process) | | |
| Interrupted publish (sent, not marked) | none | | |
| Row locked by another transaction | none | | |

## 5. Tests added (all on Testcontainers PostgreSQL; never the shared database)

Failures are injected with real PostgreSQL triggers from the test-only helper `integration/support/OutboxFaultTriggers` (no production flags):
- `rejectInsert`: an INSERT fails.
- `rejectAtCommit`: a deferred constraint trigger fails the transaction at COMMIT, after every statement ran.
- `rejectPublishedMark`: setting `published_at` fails, as if the publisher stopped after Kafka confirmed.

Every scenario checks the tables directly.

| Test class | Scenario | Result |
|---|---|---|
| `BookingOutboxAtomicityIntegrationTest` | Outbox insert fails → 503, no parcel, no event, error text not leaked | pass |
| | Parcel insert fails → 503, no event | pass |
| | Both inserts run, COMMIT fails → 503, nothing kept, no personal data in the response | pass |
| | Failed attempt, then same `Idempotency-Key` → 201, then 200; 1 parcel, exactly 1 event | pass |
| | 5 bookings + 1 commit failure → each parcel has exactly one `parcel.created`; no orphan events | pass |
| `ParcelStatusOutboxAtomicityIntegrationTest` | History insert fails → 503 `STATUS_UPDATE_UNAVAILABLE`; status, version, history and events unchanged | pass |
| | All writes run, COMMIT fails → 503, everything rolled back | pass |
| | Failed FAILED change, then retry → applied once, one event with the reason | pass |
| | 8 competing parallel transitions → every accepted change has exactly one history row and one event; event order = history order; final status = last accepted | pass |
| `MerchantOrderOutboxAtomicityIntegrationTest` (tests only; #23 code unchanged) | Outbox insert fails → 503, no parcel, no event | pass |
| | COMMIT fails → 503, nothing kept | pass |
| | Failed attempt, then same `externalOrderRef` → 201, then 200; 1 parcel, 1 event | pass |
| | 6 parallel submissions of one `externalOrderRef` → one 201, five 200, 1 parcel, 1 event | pass |
| | 422 and a conflicting replay (409) → no event | pass |
| `OutboxRestartRecoveryIntegrationTest` (PostgreSQL + Kafka; the real S2 application is started twice) | 3 bookings + PICKED_UP committed with the publisher **off**; process stopped; rows still pending; restarted with the publisher **on** → all 6 events published, each received **exactly once**, no failed attempts | pass |
| | A row locked by another open transaction is skipped for about 15 rounds (and the parcel's later event waits); after that transaction rolls back both are published once | pass |
| | Kafka confirms but "mark published" fails → the same `eventId` is received at least twice while unmarked; once marking works it is marked and no further copies arrive; still one outbox row | pass |

Already covered and not duplicated: pricing failures and 422 for bookings, booking replays and parallel same-key bookings, outbox-insert failure and repeat/invalid/terminal requests for status changes, publish under broker outage and per-parcel order (`OutboxKafkaIntegrationTest`).

## 6. Test results (actual commands)

`mvn --batch-mode --no-transfer-progress -pl parcel-pricing-service -am test` (Docker running):

```
Zentro Security Starter ... Tests run: 7,   Failures: 0, Errors: 0, Skipped: 0
parcel-pricing-service .... Tests run: 577, Failures: 0, Errors: 0, Skipped: 0   (560 before; 17 new)
BUILD SUCCESS
```

Full backend build (`mvn --batch-mode --no-transfer-progress test package` from `backend/`): see section 9.

## 7. Findings and follow-ups

| ID | Finding | Classification | Owner | Next action |
|---|---|---|---|---|
| F1 | S3 publishes no `delivery.assigned` and has no outbox; only the schema file and the README mention it | Gap (not an atomicity defect) | @Nilakshana23 | Decide whether S3 needs the event; if yes, add an S3 outbox following section 3 |
| F2 | `README.md` says "Courier marks a parcel IN_TRANSIT in S3. S3 saves the change and an outbox row in one transaction". In fact S3 calls S2's status API and S2 writes the event (#28 decision). | Documentation inaccuracy | @pirathee587 (README), @Nilakshana23 | Correct the README flow |
| F3 | A send that times out on the client although the broker stored it, or a crash between send and commit, produces a duplicate with the same `eventId` | Expected at-least-once behaviour (documented), not a defect | — | Consumers keep de-duplicating on `eventId` |

## 8. Scope split with #196

#137 (this audit): write-side atomicity and recovery of committed rows after a restart. #196 (also @14Jannan, reviewer @pirathee587): publish-after-commit verification, broker outage, retry, ordering and "no lost events" under failure. The restart test here publishes pending rows only to prove they survive; #196 can reference it.

## 9. Full build

`mvn --batch-mode --no-transfer-progress test package` from `backend/` (all modules, Docker running):

```
Zentro Security Starter ............ SUCCESS   Tests run: 7,   Failures: 0, Errors: 0, Skipped: 0
api-gateway ........................ SUCCESS   Tests run: 183, Failures: 0, Errors: 0, Skipped: 1
identity-partner-service ........... SUCCESS   Tests run: 336, Failures: 0, Errors: 0, Skipped: 0
parcel-pricing-service ............. SUCCESS   Tests run: 577, Failures: 0, Errors: 0, Skipped: 0
dispatch-courier-service ........... SUCCESS   Tests run: 145, Failures: 0, Errors: 0, Skipped: 0
tracking-reporting-service ......... SUCCESS   Tests run: 40,  Failures: 0, Errors: 0, Skipped: 0
notification-integration-service ... SUCCESS   Tests run: 380, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 10. Manual check (local PostgreSQL, real Keycloak tokens)

Setup:
- S2 on a local PostgreSQL (`localhost:5433`), never the shared database.
- Real tokens: a user holding CUSTOMER books; the dispatcher changes statuses.
- Calls go straight to S2 on 8082 (S2 validates the JWT itself).

Part B, publisher **off**:

| Check | Expected | Result |
|---|---|---|
| Booking with `Idempotency-Key`, then the same request again | 201, then 200 with the same id; exactly one `parcel.created`, unpublished | pass |
| Second booking | 201 with its own event | pass |
| Invalid booking (`weightKg: -1`) | 422; parcel and outbox counts unchanged | pass |
| PICKED_UP, then IN_TRANSIT | 200 `changed: true` each; 2 status events and 2 history rows | pass |
| Repeat IN_TRANSIT | 200 `changed: false`; no new event | pass |
| Illegal CREATED → DELIVERED | 409; no event | pass |
| Customer token on the status endpoint | 403; no event | pass |
| Totals | 4 new rows (2 created + 2 status) for 4 accepted changes, all unpublished; every parcel has exactly one `parcel.created`; no orphan events | pass |

Part C, restart: S2 stopped (4 of 4 rows still pending), Kafka started, S2 started again with `OUTBOX_PUBLISHER_ENABLED=true`:

| Check | Expected | Result |
|---|---|---|
| Pending rows after the restart | 0; every row has `published_at`, `attempts = 0` | pass |
| `kafka-console-consumer` on `parcel.*` from the beginning | 4 messages, each `eventId` exactly once, key = parcel id, headers `eventId`, `eventType`, `schemaVersion: 1`, ids equal to the database rows | pass |
| S2 logs | no names or phone numbers from the bookings | pass |
