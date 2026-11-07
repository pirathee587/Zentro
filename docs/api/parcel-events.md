# Parcel events (ZD-23)

Producer: S2 Parcel & Pricing (`parcel-pricing-service`). Issue: #58.
Consumers: S4 tracking history (#31), S5 notifications (#24), S5 merchant webhooks (#32, [merchant-webhooks.md](merchant-webhooks.md)).
Schemas: `contracts/events/event-envelope.schema.json`, `parcel-status-updated.schema.json`, `parcel-created.schema.json`.

## How events are produced

S2 uses a transactional outbox:

1. The business change and its event row in `parcel.outbox_event` are written in the same database transaction. A rolled-back change never produces an event, and a committed change always has one.
2. A background publisher reads unpublished rows and sends them to Kafka. It marks each row published only after the broker confirms it.

The publisher is off unless `OUTBOX_PUBLISHER_ENABLED=true`, so no broker is needed for tests or for environments without Kafka. Events still accumulate in the outbox while it is off and are sent once it is enabled.

## Atomicity guarantees

Verified by the ZD-062 audit (#137, [report](../test-reports/zd-062-outbox-atomicity-audit.md)), including injected database failures before, during and at commit:

- **Every producing write is atomic.** A customer booking, a merchant order and an accepted status change (with its history row) are committed in the same transaction as their outbox row. If any statement or the commit fails, neither the change nor the event exists, and the API answers `503`.
- **No event without a change, no change without an event.** Idempotent replays, conflicts (`409`), validation errors (`422`), pricing failures and repeated or illegal status changes write no outbox row. Each stored parcel has exactly one `parcel.created`; each accepted status change has exactly one `parcel.status.updated`, in the same order as the status history.
- **Committed events survive restarts.** Rows written while the publisher is off or while S2 is stopped stay in `parcel.outbox_event` and are published after the next start.
- **At-least-once, never lost.** An event is marked published only after Kafka confirms it. If S2 stops after Kafka confirmed but before the mark is committed, the same event (same `eventId`) is sent again. Consumers must de-duplicate on `eventId`.
- **Several instances are safe.** Rows are claimed with `FOR UPDATE SKIP LOCKED`; a row held by another transaction is skipped and picked up once that transaction ends.
- **S2 is the only producer.** S1, S3, S4 and S5 publish no Kafka events; S3 status changes reach Kafka through S2's status API. `delivery.assigned` is not produced yet (open item for S3).

## Topics

| Topic | Event type | Written when |
|---|---|---|
| `parcel.created` | `parcel.created` | A new customer booking (`POST /api/v1/parcels`, 201) or a new merchant order (`POST /api/v1/merchant/orders`, 201) is stored. Not for idempotent replays or conflicting replays. |
| `parcel.status.updated` | `parcel.status.updated` | An accepted status change (`PATCH /api/v1/parcels/{parcelId}/status`, `changed: true`). Not for repeats or rejected changes. |

**Not published by S2:**
- `delivery.assigned` belongs to S3 dispatch.
- `delivery.failed` is not published separately. A failed delivery is a `parcel.status.updated` event with `payload.status = FAILED` and a `failureReason`. Consumers filter on that.
- `.DLT` topics are not used. Events that cannot be sent stay in the outbox as dead rows that can be replayed (see "Failures").

**Topic creation:** when the publisher is enabled, S2 creates missing topics with `OUTBOX_TOPIC_PARTITIONS` (default 3) and `OUTBOX_TOPIC_REPLICAS` (default 1). This is for local runs. Production partitions, replication and retention belong to the deployment owner, and S2 never changes existing topics.

## Message format

| Part | Value |
|---|---|
| Key | Parcel id (`aggregateId`) as a string |
| Value | The full JSON envelope, exactly as stored in the outbox |
| Header `eventId` | Same as `envelope.eventId` |
| Header `eventType` | Same as `envelope.eventType` |
| Header `schemaVersion` | `1` |

### Envelope

```json
{
  "eventId": "6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10",
  "eventType": "parcel.status.updated",
  "occurredAt": "2026-11-03T10:15:30.123456Z",
  "aggregateId": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
  "payload": { }
}
```

`occurredAt` values are RFC 3339 UTC timestamps that always include seconds.

### `parcel.status.updated` payload

| Field | Type | Notes |
|---|---|---|
| `trackingRef` | string | `ZT-` followed by 10 characters |
| `previousStatus` | status | |
| `status` | status | One of `CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED` |
| `actorRole` | string | Role that made the change, for example `DISPATCHER` or `SERVICE` |
| `occurredAt` | date-time | |
| `failureReason` | string | Present only when `status` is `FAILED` (1-200 characters) |
| `merchantId` | string | Optional (#32). Present only for merchant parcels: the merchant account id (Keycloak subject of the merchant, 1-255 characters). Absent for customer bookings. |

### `parcel.created` payload

| Field | Type | Notes |
|---|---|---|
| `trackingRef` | string | |
| `status` | `CREATED` | |
| `source` | string | `CUSTOMER_BOOKING` for customer bookings, `MERCHANT_ORDER` for merchant orders (#23, emitted since #32) |
| `occurredAt` | date-time | |
| `merchantId` | string | Optional (#32). Present only when `source` is `MERCHANT_ORDER`: the merchant account id. |

**Personal data:** payloads never contain names, phone numbers, addresses, notes, customer ids or the id of the user who made a change. The only account identifier is the optional `merchantId` of merchant parcels, which S5 needs to find the merchant's webhook endpoint (#32). The change is additive: existing consumers ignore the field. Consumers that need contact details must ask S2 through an authorised API. The producer contract test (`ParcelEventContractTest`) enforces this together with the schemas.

## Delivery guarantees

- **At-least-once.** An event can be delivered more than once, for example after a broker timeout where the first send actually succeeded, or when an event is replayed.
- **Ordered per parcel.**
  - All events of a parcel use the same key, so they land on the same partition.
  - The publisher never sends a parcel's event while an earlier event of that parcel is still unpublished.
  - There is no ordering across different parcels.
- **Consumers must de-duplicate on `eventId`.** For example, keep a table `processed_events(consumer, event_id, processed_at)` with a primary key on `(consumer, event_id)`. Insert into it in the same transaction as the consumer's own change, and skip the event if the row already exists. `OutboxKafkaIntegrationTest` shows the pattern with a duplicate delivery.
- **Several S2 instances can run safely.** Rows are claimed with `FOR UPDATE SKIP LOCKED`, so two instances never send the same row at the same time.

## Consumer-side idempotency and error handling (S4 and S5)

Both S4 and S5 implement a consistent pattern for idempotent, resilient Kafka consumption.

### Idempotency table

Each service owns a `processed_event` table with a composite PK on `(consumer, event_id)`:

| Service | Table | Consumer name |
|---|---|---|
| S4 Tracking | `tracking.processed_event` | `tracking-reporting-service` |
| S5 Notifications | `notification.processed_event` | `notification-integration-service` |
| S5 Webhooks | `notification.processed_event` | `merchant-webhooks` |

The `markProcessed` insert uses `ON CONFLICT DO NOTHING`. If the row already exists, the consumer skips the event without side effects. The insert runs in the same transaction as the consumer's own write, so a rolled-back transaction never marks the event processed, allowing safe replay.

### Dead-letter handling (no Kafka .DLT topics)

Zentro does **not** use Kafka `.DLT` broker topics. Dead-letter and rejection handling is fully DB-backed and service-owned:

| Failure type | S4 (Tracking) | S5 (Notifications / Webhooks) |
|---|---|---|
| Malformed JSON / missing fields | Non-retryable: record discarded, `tracking.processed_event` row **not** written (replay allowed) | Inserted into `notification.rejected_event` with `reason`; event acknowledged |
| Transient DB error | Retried with bounded `FixedBackOff` (configurable `intervalMs`, `maxAttempts`) | Not acknowledged; error handler redelivers |
| Permanent outbound failure (webhooks) | n/a | `notification.webhook_delivery.status = 'FAILED'`; logged at ERROR |
| Permanent notification send failure | n/a | `notification.notification_attempt.status = 'FAILED'`; logged at ERROR |

### S4 non-retryable exceptions

The `DefaultErrorHandler` in S4 (`KafkaConsumerConfig`) classifies the following as non-retryable (immediate discard, no retry):
- `JsonProcessingException` — malformed JSON envelope
- `IllegalArgumentException` — invalid field value (e.g., non-UUID event ID)

### Operational replay

**S4 (tracking):** Replay a missed or discarded event by re-publishing it from the S2 outbox reset (see `parcel-events.md`) or by producing the event directly. If `tracking.processed_event` has no row for `(consumer_name, event_id)`, the event is processed normally.

**S5 (webhook FAILED deliveries):** Use the documented SQL reset to retry permanently failed webhook deliveries after fixing the endpoint:

```sql
UPDATE notification.webhook_delivery
   SET status = 'RETRY', attempts = 0, next_attempt_at = now(), updated_at = now()
 WHERE status = 'FAILED' AND last_error IS DISTINCT FROM 'Subscription disabled'
   AND subscription_id = (SELECT id FROM notification.webhook_subscription WHERE merchant_id = '<merchant id>');
```

This resets the delivery row in-place. No new `processed_event` row is inserted (the original is retained), and no new `webhook_delivery` row is created — the same row is retried.

## Failures and retries

- **A failed send** (broker down, timeout, unknown topic) increases `attempts` and stores a short `last_error` (never the payload). It also sets `next_attempt_at` to an exponential back-off: `OUTBOX_RETRY_BASE_DELAY_MS * 2^attempts`, capped at `OUTBOX_RETRY_MAX_DELAY_MS`.
- **The current round stops after a failed send**, so later events are not sent ahead of it.
- **An event type with no topic mapping** is recorded as failed with `unmapped event type`. It never crashes the publisher.
- **Dead events:** after `OUTBOX_MAX_ATTEMPTS` failed attempts the event is "dead". It is no longer retried, and later events of the same parcel wait behind it. It is logged at ERROR level and reported by the health check.
- **The API never depends on Kafka.** Booking and status changes succeed while the broker is down; their events are sent when it is back.

Replay dead events after fixing the cause:

```sql
UPDATE parcel.outbox_event
   SET attempts = 0, next_attempt_at = NULL
 WHERE published_at IS NULL AND attempts >= 10;   -- 10 = OUTBOX_MAX_ATTEMPTS
```

Inspect the outbox:

```sql
SELECT event_type, aggregate_id, attempts, last_error, next_attempt_at, published_at
  FROM parcel.outbox_event ORDER BY occurred_at DESC LIMIT 20;
```

## Observability

| Signal | Name |
|---|---|
| Counter | `zentro.outbox.published{eventType}` |
| Counter | `zentro.outbox.failed{eventType}` |
| Gauge | `zentro.outbox.pending`: unpublished events still being retried |
| Gauge | `zentro.outbox.dead`: events that reached the attempt limit |
| Health component | `outbox` |
| Logs | WARN for each failed attempt and ERROR when an event becomes dead. They contain the event id, type and attempt count only. |

The health component is UP with `pending`, `dead` and `oldestPendingAgeSeconds`. It is DOWN when any event is dead, or when the oldest pending event is older than `OUTBOX_STUCK_THRESHOLD_MS` (default 5 minutes).

It is part of `/actuator/health` but not of the Kubernetes `liveness`/`readiness` groups, so a broker outage never restarts S2. Health details are hidden by the existing actuator settings. For local checks, set `MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always`; this PR does not change actuator exposure.

## Configuration

All settings come from environment variables. Names are listed in `backend/parcel-pricing-service/.env.example`.

| Variable | Default | Meaning |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka brokers |
| `OUTBOX_PUBLISHER_ENABLED` | `false` | Turns publishing on |
| `OUTBOX_POLL_INTERVAL_MS` | `1000` | Pause between publishing rounds |
| `OUTBOX_BATCH_SIZE` | `50` | Events per round |
| `OUTBOX_MAX_ATTEMPTS` | `10` | Attempts before an event is dead |
| `OUTBOX_SEND_TIMEOUT_MS` | `5000` | Wait for the broker to confirm one event |
| `OUTBOX_RETRY_BASE_DELAY_MS` | `1000` | First retry delay (doubles per attempt) |
| `OUTBOX_RETRY_MAX_DELAY_MS` | `300000` | Retry delay limit |
| `OUTBOX_STUCK_THRESHOLD_MS` | `300000` | Pending age that turns health DOWN |
| `OUTBOX_TOPIC_PARTITIONS` | `3` | Partitions for topics S2 creates (local) |
| `OUTBOX_TOPIC_REPLICAS` | `1` | Replication for topics S2 creates (local) |
| `KAFKA_MAX_BLOCK_MS` | `5000` | Producer wait for metadata or buffer space |
| `KAFKA_REQUEST_TIMEOUT_MS` | `10000` | Producer request timeout |
| `KAFKA_DELIVERY_TIMEOUT_MS` | `30000` | Producer delivery timeout |

The producer uses `acks=all` and `enable.idempotence=true`.

## Open items

- **Local broker:** `backend/docker-compose.yml` has no Kafka service yet. Owner: @pirathee587. Until then, run `docker run -d --name zd23-kafka -p 9092:9092 apache/kafka:3.7.0`.
- **Kubernetes/Helm:** the Kafka deployment and production topic settings belong to the deployment owner.
- **Payload confirmation:** the payloads above, including the optional `merchantId` added in #32, still need confirmation from the S4 and S5 owners (@hephzibah-peries, @Deadsecnote1).
