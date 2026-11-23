# Role-based in-app notifications

## Audit and scope

The existing S5 `notification_attempt` and append-only delivery history support mock SMS;
merchant subscriptions, signatures, delivery/retry history and replay support webhooks.
Neither provides a per-user in-app inbox. All four portal header bells were placeholders.
There was no inbox history/read API or authenticated notification stream.

This implementation reuses S5, Keycloak JWTs, the API Gateway, PostgreSQL, the existing
S2/S3 transactional outbox publishers, React Query, the shared API client and shared UI.
Existing parcel status v1 messages, S4 tracking/SSE, mock SMS and signed webhooks are unchanged.
IN_TRANSIT continues to update tracking and merchant notifications without customer SMS.

## Notification matrix

| Recipient | Authoritative source | Inbox notifications |
|---|---|---|
| Dispatcher | S2 committed parcel insert/status; S3 assignment audit and proof review | New booking awaiting assignment; assignment accepted/rejected/cancelled/reassigned; proof submitted/resubmitted/rejected/approved; completion blocked/failed/confirmed; parcel failed/delivered |
| Customer | S2 parcel owner subject, only customer bookings | Booking, picked up, out for delivery, delivered, failed with generic safe explanation |
| Courier | S3 old/new assignment profile resolved through S1; proof revision submitter; S3 current assignee for S2 failed/delivered | Assignment and reassignment/cancellation/response; proof submitted/resubmitted/approved/rejected with reason; completion and action-required failure/blocking |
| Merchant | S2 merchant subject; own S5 webhook subscription | Created, picked up, in transit, out for delivery, delivered, failed; own webhook RETRY/FAILED state changes with safe text |

There are no independent delay/exception business states beyond FAILED and POD completion
failures in the current model. No synthetic delay events are invented. Customer-only bookings
never produce merchant inbox rows. Proof approval never produces a customer/merchant delivery
notification: only an S2 DELIVERED transition does that. Notification times describe committed
events; an old notification is history, not a substitute for the current parcel/assignment state.

## Events and transactions

New dedicated topics: `notification.parcel`, `notification.assignment`, `notification.proof`.
PostgreSQL triggers insert their minimal envelopes into the existing owning service outbox,
in the exact transaction that inserts/changes the business record. A rollback removes both.
The existing disabled-by-default relays publish them with parcel UUID keys and retries.
There is no cross-service database access, frontend event inference or historical backfill.

S2 envelopes snapshot customer/merchant ownership without contacts, names, addresses or notes.
S3 assignment envelopes contain old/new courier profile IDs; S1 resolves their subjects.
Proof envelopes use the immutable revision submitter, decision and rejection reason; no image
URLs/evidence contents. Dispatcher membership comes from S1's paginated Keycloak role directory.
Only enabled dispatcher accounts are included. S1 directory outages fail closed and retry.
S3 resolves the current assignee for authoritative failed/delivered notifications.
Restrict production Kafka topic producer/consumer ACLs to the owning services and S5;
subjects and rejection reasons are private even though these messages contain no credentials.

S5 uses a separate consumer group `in-app-inbox-v1`, an independently gated container and
unlimited bounded-delay retries. It acknowledges only after its recipient transaction commits.
The database unique key `(event_id, recipient_subject, portal)` prevents duplicate deliveries
on Kafka retries, concurrent consumers and replay. A failed identity lookup rolls back all
recipients before retry. Malformed messages block their partition rather than silently vanish;
operators must investigate/correct the source safely. Existing outbox attempt limits/dead-letter
operations remain in effect: outages longer than the configured retry budget require approved
operator recovery; no new automated historical replay is introduced.

S5's webhook status trigger creates only an owning merchant's safe operational notification,
atomically with a real transition into RETRY/FAILED. It includes no endpoint, signing secret,
response body, credentials or raw error. Repeated RETRY attempts do not spam the inbox.

## Security and APIs

All endpoints require a valid bearer JWT through the gateway and S5. `portal` selects an inbox
only after the JWT grants that exact human role; the subject always comes from the JWT.
No user ID or parcel ID supplied by the browser authorizes access. Every read/update query
includes both subject and portal. Foreign notification IDs return 404; invalid roles return 403.

* `GET /api/v1/notifications?portal=CUSTOMER&page=0`: 20 items, unread count, total and page.
* `POST /api/v1/notifications/{id}/read?portal=CUSTOMER`: idempotent own-row read.
* `POST /api/v1/notifications/read-all?portal=CUSTOMER`: own-portal unread rows only.
* `GET /api/v1/notifications/events?portal=CUSTOMER`: authenticated SSE invalidation snapshots.

Each item contains ID/type/title/message/parcelId/target/occurredAt/readAt. Items are ordered by
timestamp and UUID. SSE reads durable own-inbox counts/version every two seconds, so multiple
S5 replicas work without sticky sessions. Every reconnect receives a fresh snapshot, and read
actions in another tab update the badge. Streams expire at JWT expiry and after 90 seconds;
the client reconnects with its current bearer token. Five streams per owner/portal and 1000
per instance bound resource use. REST refresh every 30 seconds covers temporary stream outages.

New S1 `ResolveNotificationRecipients` and S3 `ResolveNotificationCourier` gRPC methods accept
only the notification SERVICE client. S1 also retains the established explicitly configured
shared-internal-token mechanism with a method-specific notification-service allowlist.
Other service identities, human dispatcher JWTs and anonymous callers cannot use the directory.

## Frontend

`shared/domain/notifications/NotificationBell.tsx` provides the shared bell, live badge, panel,
read/unread presentation, timestamps, status icons, read actions and paginated history.
It includes loading, empty, error and retry states. History routes are `/customer/notifications`,
`/courier/notifications`, `/dispatcher/notifications`, `/merchant/dashboard/notifications`.
Links point to existing portal tracking/history, orders, deliveries, parcels, proof review and
webhook pages. Navigation is restricted to the current portal prefix. Account-scoped query
keys and explicit request tokens prevent cache reuse across users or portals. The stream
cleans up on token/role changes and sends no credentials in URLs.

## Prepared migrations and activation requirements

* S2 `V11__notification_outbox.sql`: future parcel transitions into existing outbox.
* S3 `V9__notification_outbox.sql`: future assignment audits and proof reviews into existing outbox.
  Also corrects V8's reviewer constraint additively: the rejection API records a reviewer, but
  the previous constraint omitted REJECTED and prevented that transition from committing.
  Review decision/audit writes and revision/review creation are now transactional.
* S5 `V8__in_app_inbox.sql`: per-recipient inbox, indexes and safe webhook status notifications.

No existing migration was edited. S5 `V8__in_app_inbox.sql` has been applied to the shared S5
Supabase `notification` schema via controlled manual Flyway (11.7.2) after isolated backup-restore
verification; S2 `V11__notification_outbox.sql` and S3 `V9__notification_outbox.sql` remain
prepared-only (validated in isolated PostgreSQL backup-restore tests, not applied live). Note that
live S3 `dispatch.flyway_schema_history` records V8 with full-file LF CRC32 `2125172075` rather than
Flyway's line-based CRC32 `1645854167`; never run `flyway repair` or edit applied history rows.
No live workers, services, consumers or automatic Flyway settings (`SPRING_FLYWAY_ENABLED=false`,
`POD_WORKER_ENABLED=false`) were enabled. Historical ZT-TSS9BHJ6AX was neither modified nor replayed,
and there is no backfill query in these migrations.

Historical outbox replay risk: read-only inspection of the shared databases shows 26 unpublished
historical rows in S2 `parcel.outbox_event` (`10` `parcel.created`, `16` `parcel.status.updated`,
including 4 rows referencing protected tracking `ZT-TSS9BHJ6AX`) and 16 unpublished historical rows
in S3 `dispatch.outbox_event` (`10` `delivery.assigned`, `6` `delivery.accepted`). Enabling
`OUTBOX_PUBLISHER_ENABLED=true` on S2 or `OUTBOX_ENABLED=true` on S3 against the shared databases
without first scoping or settling pre-activation historical rows would immediately publish those
42 historical events (including `ZT-TSS9BHJ6AX`). Keep `OUTBOX_PUBLISHER_ENABLED`, `OUTBOX_ENABLED`,
and `INBOX_CONSUMER_ENABLED` disabled until historical outbox handling is separately approved.

After separate deployment authorization: review/apply additive migrations using the established
process; provision the three topics/ACLs; configure S5 S1/S3 gRPC connectivity and SERVICE token;
ensure S1 has read permission for Keycloak dispatcher role membership. The optional local Kafka
compose topic provisioner now includes the notification and existing assignment topics.
Set `INBOX_CONSUMER_ENABLED=true` only in the approved target environment. Source publishers
still require their existing OUTBOX_ENABLED setting; no default has been changed. Do not turn
on POD completion/recovery workers to activate notifications. The legacy proof bookkeeping
worker is not replayed or changed; review notifications concern the versioned POD review flow.

This is a local implementation. A browser demonstration through deployed Keycloak, gateway,
and Kafka remains a deployment-stage check; passing isolated tests does not prove live delivery.

## Focused validation

`InboxIntegrationTest` uses disposable PostgreSQL and actual S2/S3/S5 migrations: all four
recipient mappings, source-event atomicity/rollback, approval without false delivery, rejection
reason, customer-only isolation, duplicate inserts, pagination, restart persistence, ownership
and portal checks, read-all, SSE changes and reconnect snapshots.

`InboxKafkaIntegrationTest` uses disposable Kafka/PostgreSQL: retry after recipient-directory
failure with rollback, duplicate records, and broker restart recovery of pending publication.
Existing S1/S3 interceptor tests also cover the notification-only recipient RPC allowlists.
Frontend tests cover four portals, read actions, stream invalidation, empty/error history and
the shared HTTP/SSE transport boundary. See final task report for executed results.

Executed locally on 2026-11-22: inbox PostgreSQL tests 5/5; Kafka integration 2/2;
S1/S3 RPC authorization 12/12; gateway behavior/inventory 17/17; existing S2 booking,
status and merchant-order transaction regression tests 14/14; frontend notification,
transport and all four header tests 21/21. No container tests were skipped. Frontend
production build/typecheck passed; lint passed with nine pre-existing warnings.
The production build retains its existing large-chunk advisory.
