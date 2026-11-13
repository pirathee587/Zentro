# G16 courier notification contract — PROPOSED, implementation BLOCKED

Date: 2026-11-14. Refs #349 and #356. Source:
`b7fc2f72bc63406940cf8bf6bc2b1bdbc317ab22` on develop.
Owner @Deadsecnote1; independent reviewer @Kiruthiyan. Product/security/domain approvals
below remain unrecorded. This document is a technical proposal, not release authorization.

## Verified existing contracts

S3 `dispatch/repository/AssignmentRepository.writeAuditAndEvent` writes assignment changes,
assignment_audit and outbox_event within the same transaction. Topics are delivery.assigned,
delivery.reassigned and delivery.cancelled; Kafka key is parcel aggregateId. The publisher
sets schemaVersion=1. Audit and envelope IDs are different; there is no assignment revision
or assignment ID in the existing lifecycle payload. IDs called oldCourierId/newCourierId
refer to courier profiles, not necessarily JWT subjects. A rejected assignment does not
establish an assignment-failed notification event; do not invent one.

Current wire example (synthetic UUIDs; schemaVersion is a header, not a new envelope field):

```json
{"eventId":"11111111-1111-4111-8111-111111111111","eventType":"delivery.reassigned","occurredAt":"2026-11-01T10:00:00Z","aggregateId":"22222222-2222-4222-8222-222222222222","payload":{"action":"REASSIGNED","oldCourierId":"33333333-3333-4333-8333-333333333333","newCourierId":"44444444-4444-4444-8444-444444444444","actorSubject":"synthetic-dispatcher"}}
```

Do not add contacts, trackingRef, assignment ID or sequence to this schema without main 02
and S3 review. Preserve correlation through the approved propagation headers; do not assume
every historical record carries one. OccurredAt alone cannot order simultaneous events.

S5 currently consumes parcel.created/status.updated only. Its RecipientResolver resolves a
tracking reference, ChannelPreferenceService supports MOCK_SMS, and customer-worded templates
are not courier notices. NotificationSender already exists. No person-contact preference or
consent persistence contract exists. Current mock behavior is not real courier delivery.

S1's Dispatcher courier API and Courier self-profile API are not service contact-directory
APIs. Existing gRPC GetCourierProfile returns courier ID/active status, not a notification
destination. Its existing authentication must not be bypassed or silently widened.

## Approval register

Every row is **PROPOSED / BLOCKED**; approval needs an explicit reference, person and date.

| Decision | Options and recommendation | Required approver | Consequence |
|---|---|---|---|
| Release/recipient matrix | Defer courier delivery; mock-only characterization; approved external notices. Recommend explicit release decision before a consumer. For reassignment consider new courier assignment plus old courier removal; cancellation to former courier, all subject to product approval | Product/security with S3/S5 | No assignment subscriptions or sends enabled by this proposal |
| Schema/version/order | Existing IDs/time only; add monotonic parcel assignment version; authoritative assignment lookup. Recommend S3-owned causal version and an approved current-state API if stale-send prevention is required | S3 owner/main 05, shared contracts main 02, S5 | No payload mutation; migration/outbox changes remain S3-owned |
| S1 recipient lookup | Contacts in Kafka; bounded S1 purpose-specific lookup; provider-owned recipient token. Recommend purpose-specific S1 lookup after security approval | S1 owner/main 01, main 02, security/privacy | New route/claims/data fields must be approved before S5 client implementation |
| Channels/consent | Mock only; SMS/email after explicit eligibility. Recommend do not send externally when required consent/eligibility cannot be established | Product/privacy/security/S1 | No assumed consent or new opt-out table; coordinate with X06 |
| Templates | Reuse customer text; approve courier templates. Recommend minimal courier wording with approved resource reference | Product and S3/S5; privacy for variables | Strings below are drafts, not active templates |
| Retry/replay | Reuse durable outcome model; define lookup retry budget and terminal categories separately from Kafka storage retry | Product/security/S1/S5 | Never turn S1 outage into permanently processed SKIPPED by accident; no finite lossy storage exhaustion |

## Proposed least-privilege lookup — NOT AN EXISTING ENDPOINT

Candidate contract for review, owned by S1:

```http
GET /internal/v1/notification-recipients/{courierProfileId}?purpose=ASSIGNMENT&channel=SMS
Authorization: Bearer <dedicated S5 service credential>
```

Illustrative minimum response (placeholder only, no real contact):

```json
{"courierProfileId":"44444444-4444-4444-8444-444444444444","channel":"SMS","eligible":true,"destination":"<approved-contact-returned-only-over-authenticated-transport>","preferenceVersion":"<approved-version>"}
```

Security must approve caller identity/audience/scopes, allowed purpose and field minimization;
SERVICE role alone must not grant arbitrary directory reads. Bind the returned profile to
the requested profile, reject mismatch, and never fall back to a different recipient or a
Dispatcher/self-profile API. Define S3 assignment authorization proof if S1 must validate
assignment eligibility; no such proof format exists yet. An opaque destination token is an
alternative if provider/S1 contracts support it, not a new mandatory architecture.

Proposed response categories: unauthenticated/unauthorized fails closed; missing/ineligible
recipient yields an approved safe no-send outcome; S1 timeout/429/5xx remains retryable and
observable. Exact errors, deadlines, retry budgets and persisted outcome categories await
approval. Never cache contacts indefinitely or log bodies/URLs containing identifiers.
Prefer short-lived in-memory destination use; durable contact encryption/TTL needs its own
privacy decision. S1 lookup failure must not block unrelated parcels indefinitely: recovery
storage/scheduling must be designed before consuming and acknowledging assignment events.

## Proposed outcome/order/replay semantics

Reuse S5's transactionally persisted event deduplication and post-commit sending pattern.
A proposed logical key is eventId + purpose + courierProfileId + channel, allowing two
explicitly approved outcomes for reassignment without merging old/new recipients. This
would require an approved S5 schema extension; current single mock-recipient behavior is
not claimed to support it already.

If product requires stale suppression, validate authoritative current assignment/version
immediately before send. Missing/unreachable authority means postpone or approved no-send,
not guessed ownership. Ordering on Kafka parcel key helps only inside one topic/partition;
three different topics do not provide one causal order. Timestamp comparison is insufficient.
Cancellation/reassignment must invalidate pending stale work under the approved version rule.
A race remains between state verification and external acceptance; product must define its
acceptable window and receiver/provider dedup semantics. No exactly-once promise.

Manual replay retains event identity, rechecks current assignment/eligibility/consent, and
requires existing Dispatcher authorization plus an approved scoped audit rule. Do not use
a new event ID to bypass deduplication. A revoked preference cannot be overridden by replay.
No replay route or assignment recovery endpoint is implemented in this proposal.

## Draft templates — NOT APPROVED OR ACTIVE

| Purpose | Draft text | Proposed variables |
|---|---|---|
| New assignment | A delivery task {resourceReference} has been assigned to you. | Approved non-contact reference only |
| Reassignment removal | Your assignment for delivery task {resourceReference} has ended. | Same reference |
| Cancellation | Delivery task {resourceReference} has been cancelled. | Same reference |

Product must choose which purposes/recipients actually apply. Current events carry parcel
UUIDs, not tracking references; do not invent a user-facing reference lookup. Validate all
variables against the approved template schema, reject missing/oversized/control-character
values, and never interpolate free-form event payload or contact fields. Destination and
message text must remain excluded from logs/audit responses.

## Verification and release gates

After approvals, add producer fixtures from S3's real envelopes; test duplicate IDs,
reassignment before send, cancellation, cross-topic reorder, equal timestamps, old-event
replay, S1 outage/recovery, recipient mismatch, missing/withdrawn consent, unsupported
channels, restart and send-before-commit denial. Test simultaneous workers and provider
acceptance-before-commit with approved receiver idempotency. No contract-test pass is
claimed from a proposed example. Current S3 publisher reliability is exercised by existing
S3 tests; no competing publisher or outbox implementation is added.

Proposed configuration uses existing disabled-by-default consumer/provider conventions;
no new environment variable is active. Release requires exact approved defaults, credential
ownership and failure budgets. Rollback future rollout by disabling only the approved
assignment consumer, retaining durable pending work/evidence; do not delete dedup rows.
This document is safely reversible without runtime effects. No API, event, database,
permission or consent policy changed, and no blocked runtime tests are claimed.
