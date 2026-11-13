# S5 notification and webhook recovery

Scope: existing parcel notifications (`MOCK_SMS`) and merchant webhooks. Courier delivery,
real email/SMS and general operational audit ingestion remain policy-blocked under #356.
Mock acceptance is not external contact delivery. This runbook changes no retention policy.

## Delivery and failure boundaries

1. A valid parcel event claims `(consumer, eventId)` and stores its logical outcome in one
   S5 database transaction. Kafka acknowledgement follows the commit. Failed storage is
   retried every five seconds without exhaustion; a partition can remain blocked during
   an outage. Do not replace this with finite retries that commit an unpersisted record.
2. The initial delivery follows acknowledgement. If acknowledgement or initial delivery
   fails, committed PENDING/RETRY rows remain available to the scheduler. A duplicate
   event does not create another logical outcome.
3. Provider retries have a separate finite budget. Their exhaustion becomes FAILED.
   Database errors while recording success must escape and roll back the transaction;
   they must not be classified as rejected provider calls or consume that budget.
4. An accepted send and its database result are not atomic. A crash/rollback between
   them can send twice, even though only one logical outcome and one committed attempt
   exist. Attempt history describes committed results, not every network call. Counters
   are process-local and are not authoritative transaction evidence.
5. Webhook attempts lock the delivery with `FOR UPDATE ... SKIP LOCKED`. Earlier open
   deliveries for the same subscription/parcel hold back later ones according to stored
   creation time; this is not a source-business-time reordering guarantee. Parcel mock
   notifications do not suppress a distinct older event just because a later status arrived.

## Rejected records are not a DLT

Malformed envelopes/unsupported schemas are recorded in `notification.rejected_event`
with topic, partition, offset and a bounded parser reason; their payload is neither stored
there nor logged. Acknowledgement occurs only after the rejection transaction commits.
If rejection storage fails, the listener throws and retries rather than dropping the record.
There is no DeadLetterPublishingRecoverer or supported DLT redrive endpoint. Replaying the
same invalid data will reject it again and can add another rejection row. Do not describe
rejection metadata as a recoverable payload archive.

## Diagnose an outage

Use authorized operational access to S5's own database and Kafka tooling. Capture the
build revision, incident interval, topic/partition/offset and event IDs without copying
payloads, contacts, tokens, signing secrets or rendered messages into incident logs.

- Check database connectivity, Kafka availability, consumer group lag and committed offsets.
- Inspect `/actuator/health` and the existing `notifications`/webhook health components.
- Through the gateway, Dispatcher JWTs can read
  `GET /api/v1/admin/notification-attempts?page=0&size=25` and
  `GET /api/v1/admin/webhook-deliveries?page=0&size=25` (maximum size 100).
  These endpoints return logical summaries, not a general audit API or all attempt rows.
- Existing notification meters include `zentro.notifications.pending`,
  `zentro.notifications.failed.total`, `.rejected`, `.retried` and `.duplicates`.
  Compare repeated observations with consumer lag and oldest open work. Unknown/NaN
  database-backed gauges during an outage are not a zero backlog. The current Prometheus
  registry reports a naming collision between the failed counter and `failed.total` gauge;
  do not rely on that gauge being exported. Use authenticated summaries/health and the
  counter with its reset semantics until metric naming is coordinated separately.
- Escalate growing lag, repeated rejection, terminal delivery failures, or old open work
  to the service owner using the existing incident process. No new alert rules, thresholds,
  dashboards or collectors are installed by this change; those belong to main package 03.

Restore the failed dependency first. Keep consumer group identity and database state
intact, then allow the existing listener and scheduler to recover. Compare committed
outcomes to an independent source event-ID ledger, not merely to success counters.

## Replay supported by the existing contract

**Valid Kafka event replay:** replay the original approved envelope, stable event ID and
parcel key to its original topic through authorized operational tooling. Reusing the same
ID deduplicates S5 ingestion. Inventing a new ID bypasses this protection. A duplicate
Kafka replay does not reset a FAILED notification or webhook. Coordinate Kafka offset
changes with the Kafka owner and stop all group members first; an offset reset affects
all consumers of that group and is not an S5 API operation. No offset-reset command or
rejected-payload recovery API is provided here.

**Failed webhook replay:** after fixing the cause and confirming receiver deduplication,
an authorized Dispatcher can call through the gateway:

```text
POST /api/v1/admin/webhook-deliveries/{deliveryId}/replay
Authorization: Bearer <Dispatcher JWT supplied securely>
```

Success is 204; unknown IDs return 404; ineligible states return 409. Existing behavior
allows one replay per delivery, excludes rows closed with `Subscription disabled`, resets
the policy attempt count, and records the dispatcher subject in
`notification.webhook_delivery_replay` in the same transaction. Append-only delivery
attempt history retains its cumulative numbering across that reset. A currently disabled
subscription will be closed without sending by the worker. Repeated replay requests do
not create repeated replay authorizations. Do not manually delete replay or dedup rows.
There is no notification replay endpoint; escalation is required for FAILED mock outcomes.

## Receiver contract and remaining limits

Webhook receivers must validate the existing signature/timestamp and use the stable
`X-SwiftDrop-Event-Id` identity to atomically deduplicate
business effects with their own durable state. A receiver must respond successfully for an
already accepted event. Delivery retries/replay retain event identity while signing again.
S5 does not guarantee that a merchant implements this contract. Confirm receiver support
before replay; lack of support means at-least-once side effects and possible duplication.
No receiver retention period or new provider idempotency contract is approved here.

The recovery tests use throwaway PostgreSQL/Kafka containers, a mock notification sender,
and synthetic transaction failures. A separate JVM drill kills and restarts the production
S5 application against those fixtures and recovers committed pending work. This local
process drill does not demonstrate deployment orchestration or a kill at the exact instant
of external acceptance. Synthetic failure
after mock acceptance demonstrates the duplicate-send window, not real SMS delivery or
external receiver idempotency. See the #356 verification report for executed evidence and
unverified scenarios.


## Rejection inspection and DLT decision boundary (2026-11-14)

Authorized operators may inspect bounded metadata in S5's own database:

```sql
SELECT topic, partition_no, offset_no, reason, received_at
FROM notification.rejected_event
WHERE received_at >= :incident_start AND received_at < :incident_end
ORDER BY received_at DESC, id DESC
LIMIT 100;
```

The topic/partition/offset tuple is the original record reference when event identity cannot
be parsed. No raw event or persisted retry count is available here. Inspect original Kafka
content only under the Kafka owner's approved access/retention process; do not paste payloads
into incident logs. Broker retention can make reconstruction impossible. No DLT topic is
created or populated by S5, so DLT tooling has no supported S5 topic to inspect/redrive.
A FAILED webhook summary is not a Kafka dead-letter record.

A proposed future DLT decision must specify payload minimization/encryption, access,
retention, failure categories, original event/position/version/correlation metadata, attempt
information, atomic publication-versus-offset recovery, and authenticated deduplicated redrive.
No production topic name, retention period or finite retry exhaustion is selected here.
Do not disable unlimited database storage retry merely to avoid a blocked partition: without
an approved durable quarantine/outbox transition that would permit message loss. Existing
five-second backoff and incident escalation bound retry pressure, not the number of attempts.
Poison records use durable rejection instead; finite provider attempts use FAILED outcomes.

Additional evidence in NotificationRecoveryIntegrationTest sends two actual loopback HTTP
requests after a synthetic post-acceptance database rollback. A synthetic receiver atomically
claims the event ID and applies one logical effect; S5 commits one outcome. The receiver's
claim set is in memory: this proves deduplication while that receiver runs, not persistence
across receiver restart, real-provider delivery, or deployed merchant behavior. The database
failure is injected, not a process kill at the exact network boundary. A separate test
verifies rejection-write failure leaves the poison record unacknowledged, then records a
safe rejection before acknowledging on recovery. Replay authorization tests include an
unauthenticated POST in addition to the existing non-Dispatcher role matrix.
