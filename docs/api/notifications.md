# Parcel notifications (ZD-09)

Service: S5 Notification & Integration (`notification-integration-service`, port 8085). Issue: #24.
Producer of the events: S2 Parcel & Pricing ([parcel-events.md](parcel-events.md), #58).

S5 turns parcel lifecycle events into customer notifications and records every outcome. This task uses a **mock SMS** channel. Real SMS or email providers, managed templates (#64) and a reports UI (#74) are separate work.

## Consumed topics

| Topic | Schema |
|---|---|
| `parcel.created` | `contracts/events/parcel-created.schema.json` |
| `parcel.status.updated` | `contracts/events/parcel-status-updated.schema.json` |

| Setting | Value |
|---|---|
| Consumer group | `${KAFKA_CONSUMER_GROUP:notification-integration-service}` |
| Offsets | Committed manually, only after the event is stored (`enable-auto-commit: false`, `ack-mode: manual`) |
| Start position | `earliest` for a new group |
| Switch | `NOTIFICATIONS_CONSUMER_ENABLED`, default `false`. While off, S5 never connects to Kafka. |

## Which statuses notify (D2)

Messages are simple fixed texts; template management is #64.

| Event / status | Outcome | Message |
|---|---|---|
| `parcel.created` (`CREATED`) | Sent | `Your SwiftDrop parcel <ref> has been booked.` |
| `PICKED_UP` | Sent | `Your parcel <ref> has been picked up.` |
| `IN_TRANSIT` | **SKIPPED** (recorded, nothing sent) | none |
| `OUT_FOR_DELIVERY` | Sent | `Your parcel <ref> is out for delivery.` |
| `DELIVERED` | Sent | `Your parcel <ref> has been delivered.` |
| `FAILED` | Sent | `Delivery of parcel <ref> was not completed: <failureReason>.` |

**Recipient (D1).** Parcel events carry no contact details, so each notification is addressed by tracking reference only (`to=parcel ZT-...`). S5 stores and logs no names, phone numbers or addresses. Sending to a real person needs a contact lookup in S2; that is a follow-up (owner @14Jannan).

## Processing rules

1. **Parse and validate the event.** Every field is checked:
   - `eventId` and `aggregateId` are UUIDs
   - `eventType` matches the topic
   - `occurredAt` is a date-time
   - `trackingRef` matches `ZT-` plus 10 characters
   - `status` is a known status
   - `failureReason` is present for `FAILED` only
2. **Malformed message:** a row is written to `notification.rejected_event` with topic, partition, offset and a short reason. The raw payload is never stored or logged. The message is acknowledged, so it never blocks the partition and is never retried.
3. **Valid event, in one transaction:**
   - Insert `(consumer, eventId)` into `notification.processed_event`. If the row already exists, the event is a **duplicate**: it is counted and nothing else happens. The database primary key decides, so concurrent consumers and redeliveries are safe.
   - Store a `SKIPPED` row, or a `PENDING` notification with the message.
4. **Acknowledge** the Kafka message after the commit, then make the first delivery attempt. A send therefore never belongs to a transaction that later rolls back.
5. **Unexpected error while storing** (for example the database is down): the message is not acknowledged. The error handler redelivers it every 5 seconds with no limit, so no event is lost.

## Retries

- Sending never sleeps inside the Kafka listener. A failed attempt sets the row to `RETRY` with:
  - `attempts + 1`
  - a short `last_error` (no message text)
  - `next_attempt_at = now + NOTIFICATION_RETRY_BASE_DELAY_MS * 2^(attempts - 1)`, capped at `NOTIFICATION_RETRY_MAX_DELAY_MS`. The defaults are 5 s, 10 s, 20 s and so on, up to 10 minutes.
- A scheduler (every `NOTIFICATION_RETRY_POLL_MS`) re-sends due `RETRY` rows. It also picks up `PENDING` rows left behind by a crash between storing and sending. Rows are locked with `FOR UPDATE SKIP LOCKED`, so several instances can run safely.
- After `NOTIFICATION_MAX_ATTEMPTS` failed attempts the row becomes `FAILED`. It is not retried again, and an ERROR log line is written.

Notification row states: `PENDING` → `SENT` | `RETRY` → … → `SENT` | `FAILED`; or `SKIPPED`.

## Observability

| Signal | Name |
|---|---|
| Counters | `zentro.notifications.sent`, `.failed`, `.retried`, `.skipped`, `.duplicates`, `.rejected` |
| Gauges | `zentro.notifications.pending` (PENDING + RETRY rows), `zentro.notifications.failed.total` (FAILED rows) |
| Health component | `notifications` |
| Logs | INFO for a sent or skipped notification, WARN for a failed attempt or a rejected message, ERROR when a notification fails for good. They contain the event id, tracking reference, status and attempt count only; never the message text or the raw event. |

The health component reports `pending`, `failedRecently` and `oldestPendingAgeSeconds`. It is DOWN when a notification failed within the last `NOTIFICATION_HEALTH_FAILED_WINDOW_MINUTES` (60), or when the oldest waiting notification is older than `NOTIFICATION_HEALTH_STUCK_PENDING_MINUTES` (10).

It is part of `/actuator/health` but not of the Kubernetes liveness/readiness groups. Health details are hidden by the existing settings; for local checks, set `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`.

**No HTTP endpoint (D3).** S5 adds no API. Only `/actuator/health` is public, and every other path requires a token. Outcomes are inspected with SQL, metrics, health and logs. A reports UI is #74.

## Inspecting outcomes

```sql
-- Latest notifications
SELECT tracking_ref, parcel_status, status, attempts, last_error, next_attempt_at, created_at
  FROM notification.notification_attempt ORDER BY created_at DESC LIMIT 20;

-- Everything for one parcel
SELECT parcel_status, status, attempts, message, created_at
  FROM notification.notification_attempt WHERE tracking_ref = 'ZT-XXXXXXXXXX' ORDER BY created_at;

-- Rejected (malformed) messages
SELECT topic, partition_no, offset_no, reason, received_at
  FROM notification.rejected_event ORDER BY received_at DESC LIMIT 20;

-- Retry FAILED notifications again after fixing the cause
UPDATE notification.notification_attempt
   SET status = 'RETRY', attempts = 0, next_attempt_at = now(), updated_at = now()
 WHERE status = 'FAILED';
```

## Configuration

Values come from environment variables. Names, with no real values, are listed in `backend/notification-integration-service/.env.example`.

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `KAFKA_CONSUMER_GROUP` | `notification-integration-service` | Consumer group |
| `NOTIFICATIONS_CONSUMER_ENABLED` | `false` | Turns the consumer and the retry scheduler on |
| `NOTIFICATION_MAX_ATTEMPTS` | `5` | Attempts before a notification is FAILED |
| `NOTIFICATION_RETRY_POLL_MS` | `5000` | Pause between retry rounds |
| `NOTIFICATION_RETRY_BASE_DELAY_MS` | `5000` | First retry delay (doubles per attempt) |
| `NOTIFICATION_RETRY_MAX_DELAY_MS` | `600000` | Retry delay limit (10 minutes) |
| `NOTIFICATION_MOCK_FAIL_MODE` | `none` | `none`, `always` or `first-attempt`. Only for testing retries locally. |
| `NOTIFICATION_HEALTH_FAILED_WINDOW_MINUTES` | `60` | FAILED notifications in this window turn health DOWN |
| `NOTIFICATION_HEALTH_STUCK_PENDING_MINUTES` | `10` | Waiting longer than this turns health DOWN |

The database schema is `notification` (Flyway migration `V1__notification_schema.sql`). S5 also uses the shared security starter, so `JWT_ISSUER_URI` and `JWT_AUDIENCE` (or `JWT_JWKS_URI`) must be set, as for the other services.

## Follow-ups

| Item | Owner |
|---|---|
| Recipient contact lookup, which needs an S2 API | @14Jannan |
| Notification templates and wording | #64 |
| Delivery reports dashboard | #74 |
| Kafka service in `backend/docker-compose.yml` | @pirathee587. Until then: `docker run -d --name zd09-kafka -p 9092:9092 apache/kafka:3.7.0` |
