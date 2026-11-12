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

The current templates are constants in `NotificationMessages` with a purpose-built renderer. Only the existing
`MOCK_SMS` sender is supported; this service has no email sender, so there are no email subject or HTML parts.

| Event / status | Outcome | Message |
|---|---|---|
| `parcel.created` (`CREATED`) | Sent | `Your SwiftDrop parcel <ref> has been booked.` |
| `PICKED_UP` | Sent | `Your parcel <ref> has been picked up.` |
| `IN_TRANSIT` | **SKIPPED** (recorded, nothing sent) | none |
| `OUT_FOR_DELIVERY` | Sent | `Your parcel <ref> is out for delivery.` |
| `DELIVERED` | Sent | `Your parcel <ref> has been delivered.` |
| `FAILED` | Sent | `Delivery of parcel <ref> was not completed: <failureReason>. Please contact support for help.` |

Template ids are `parcel.created.mock-sms.v1` and `parcel.status.updated.mock-sms.v1`. The status-updated template
selects the wording above from the validated `status` field. Variables are:

| Template | Required | Optional | Validation / fallback |
|---|---|---|---|
| `parcel.created.mock-sms.v1` | `trackingRef` | none | Must match the producer contract's `ZT-` plus 10 Crockford Base32 characters. |
| `parcel.status.updated.mock-sms.v1` | `trackingRef`; `failureReason` when status is `FAILED` | none | Tracking reference uses the same contract format; failed reason must be nonblank and at most 200 Unicode code points. Display is capped at 140 code points with an ellipsis. |

There are no optional template variables in the current event payloads, so no fallback text is needed. SMS output removes
control, bidi and zero-width format characters from variable text. User values are inserted literally and are never
evaluated as template expressions or turned into links. Missing or invalid variables return a typed validation result;
the consumer stores a safe `SKIPPED` outcome and acknowledges the event. Unsupported event types also produce a safe
skip result. The existing parser rejects malformed Kafka payloads and acknowledges them once recorded, preserving the
consumer's existing no-retry behavior for bad messages. Logs contain template/event categories and failure field
names only, not tracking references or rendered content.

**Recipient resolution and channel preferences (ZD-065 / D1).** Parcel events carry no personal contact details (names, phone numbers or addresses), so each notification is resolved to its validated tracking reference (`to=parcel ZT-...`). Recipient resolution validates Crockford Base32 tracking references (`^ZT-[0-9A-HJKMNP-TV-Z]{10}$`) and rejects malformed references or injection payloads without leaking raw content. S5 evaluates channel preferences against the supported delivery channel (`MOCK_SMS`); when unsupported channels (e.g. `EMAIL`) are requested without a supported fallback, S5 records a typed `SKIPPED` outcome ("Channel preference not supported") and increments skipped metrics without throwing unhandled runtime exceptions.

## Processing rules

1. **Parse and validate the event.** Every field is checked:
   - `eventId` and `aggregateId` are UUIDs
   - `eventType` matches the topic
   - `occurredAt` is a date-time
   - `trackingRef` matches `ZT-` plus 10 characters
   - `status` is a known status
   - `failureReason` is present for `FAILED` only
2. **Resolve recipient and channel preferences (ZD-065).** Tracking reference must resolve successfully; channel preference must evaluate to an available channel. Invalid recipients or unsupported preferences record a safe `SKIPPED` attempt.
3. **Malformed message:** a row is written to `notification.rejected_event` with topic, partition, offset and a short reason. The raw payload is never stored or logged. The message is acknowledged, so it never blocks the partition and is never retried.
4. **Valid event, in one transaction:**
   - Insert `(consumer, eventId)` into `notification.processed_event`. If the row already exists, the event is a **duplicate**: it is counted and nothing else happens. The database primary key decides, so concurrent consumers and redeliveries are safe.
   - Store a `SKIPPED` row, or a `PENDING` notification with the message.
5. **Acknowledge** the Kafka message after the commit, then make the first delivery attempt. A send therefore never belongs to a transaction that later rolls back.
6. **Unexpected error while storing** (for example the database is down): the message is not acknowledged. The error handler redelivers it every 5 seconds with no limit, so no event is lost.

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

**HTTP endpoints.** S5 has no notification-content API, but it does expose two DISPATCHER-only operational history endpoints through the gateway: `GET /api/v1/admin/notification-attempts` and `GET /api/v1/admin/webhook-deliveries`. They return outcome metadata and do not expose message bodies or webhook payloads. See the [REST API index](README.md) and [S5 OpenAPI contract](openapi/notifications.openapi.json). Only `/actuator/health` is public; business operations require their documented bearer role. A richer reports UI is #74.

## Inspecting outcomes

Dispatcher read models are available through the gateway:

- `GET /api/v1/admin/notification-attempts?page=0&size=25`
- `GET /api/v1/admin/webhook-deliveries?page=0&size=25`

Both require a valid `DISPATCHER` token. They return outcome metadata only (event and parcel identifiers,
status, attempt count, error category, retry time and timestamps); notification content, webhook payloads,
URLs and signing secrets are never returned. The dispatcher UI uses these read models to show loading,
empty, retry/error and terminal `FAILED` states.

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
| `NOTIFICATION_MOCK_FAIL_MODE` | `none` | `none`, `always`, `first-attempt`, `timeout`, `throttled` or `permanent`. Only for testing provider recovery locally. `timeout` and `throttled` are retryable provider failures; `permanent` remains observable as `FAILED` after the configured attempt limit. |
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
