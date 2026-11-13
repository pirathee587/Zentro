# G15 operational audit — PROPOSED, implementation BLOCKED

Date: 2026-11-14. Refs #348 and #356. Inspected develop:
`b7fc2f72bc63406940cf8bf6bc2b1bdbc317ab22`.
Implementation owner: @Deadsecnote1. Independent reviewer: @Kiruthiyan.
Product/security approvers are responsible roles, not recorded individual approvals.
Merging this technical proposal must not be treated as approval of its policy choices.

## Context and current implementation

The gateway reserves `/api/v1/admin/audit-logs/**` for S5, but S5 has no handler or
migration for a general audit stream. Existing Dispatcher notification/webhook history
is logical delivery history, not a general audit API. S1's `identity.user_admin_audit`
(V6) is append-only, with field names/statuses and a free-text reason. S3's
`dispatch.assignment_audit` (V4) is separate from its transactional lifecycle outbox.
S5 has `notification.delivery_attempt_history` (V4) and one-time webhook replay evidence
(V5). These facts have different identities and protections; copying their raw rows
would leak fields and invent ownership. There is no approved cross-service export.

## Candidate source inventory — not a retention allowlist

Identifiers below are restricted operational/pseudonymous data, not anonymous data.
An absent actor or correlation ID must remain absent; ingestion must not fabricate one.

| Candidate fact | Source / identifier | Actor / target / time | Correlation and minimum metadata | Classification and boundary |
|---|---|---|---|---|
| parcel.created | S2 envelope eventId | Actor absent; aggregateId parcel; occurredAt | Existing propagation metadata where carried; eventType, schemaVersion, source category | Restricted resource identity; exclude trackingRef, merchantId and payload by default |
| parcel.status.updated | S2 envelope eventId | Actor role only, not actor identity; parcel; occurredAt | Propagation where present; previousStatus/status | Restricted operational; never copy failureReason free text |
| delivery.assigned/reassigned/cancelled | S3 envelope eventId | actorSubject; aggregateId parcel; occurredAt | Outbox schemaVersion=1; action; courier references only if approved | Actor/courier IDs are restricted personal identifiers; no contacts |
| user profile/status administration | S1 user_admin_audit.id | actor_user_id/actor_subject; target_user_id; occurred_at | No approved export/correlation contract; changed field names/status categories only | Security-sensitive personal identifiers; omit reason and profile values |
| committed delivery attempt outcome | S5 delivery_attempt_history composite key | Actor absent; delivery/event ID; started/finished timestamps | delivery_kind, outcome, response_class, policy attempt and cumulative attempt | Internal operational; no message, payload, tracking reference, error text or provider identifiers |
| authorized webhook replay | S5 webhook_delivery_replay.delivery_id | dispatcher_subject; delivery ID; replayed_at | Link to delivery's event identity only through S5-owned data | Restricted operator identity; existing row is not a general audit export |
| authentication/authorization security events | Identity/Keycloak security owner; no approved event ID/export | Actor/target/time depend on approved source | No ingestion contract exists | Exclude initially; never invent a login audit stream or copy tokens/IPs |

## Decisions requested

All statuses below are **PROPOSED / BLOCKED**. Required approvers must record a decision
reference, identity and date for each row before runtime expansion.

| Decision | Options | Recommendation | Required approver | Implementation consequence |
|---|---|---|---|---|
| Sources/ownership | Reuse local APIs; explicit redacted domain exports; copy raw payloads | Explicit allowlisted exports owned by each producer, S5 owns its projection; start with a separately approved small source set | Product, security, S1/S2/S3/S5 owners, shared contracts owner (main 02) | No consumer/schema/table until approved; never shared SQL |
| Access | All Dispatchers; Dispatcher plus existing permission mapping; service ingestion only | Dispatcher read plus a separately confirmed audit permission if identity already supports it; no SERVICE read by implication | Security/identity and product | Do not invent AUDIT_VIEW authority or treat SERVICE as Dispatcher; ingestion credentials distinct from read access |
| Read query contract | Offset; opaque keyset cursor | Cursor ordered occurredAt DESC, eventId DESC, page default 25/max 100; fixed ingestion upper-bound in cursor | Product/API owner and main 02 | Stable bounded queries; late/backdated events can appear only in a fresh traversal; no snapshot guarantee from keyset alone |
| Filters | Unbounded history; required bounded date range | Proposed UTC from-inclusive/to-exclusive window, at most 31 days; exact eventType/resourceId; actor filter only after access approval | Product/security/API owner | Reject invalid/inverted ranges, oversized windows and unknown filters; bounds are proposals, not active limits |
| Redaction | Scrub arbitrary JSON; store strict typed safe metadata | Allowlist per event version; reject disallowed fields; omit free text and personal contact data before persistence | Security/privacy with producers | Test secrets/contact canaries absent from DB, response and logs; unknown versions recorded safely |
| Retention | 30, 90 or 365 days; business-justified alternative; approved archival | Discuss 90 days of searchable operational data as a planning candidate only; choose no active TTL until legal/product/security acceptance | Product and privacy/legal/security; infrastructure owner for archives | No default period, purge job, irreversible deletion or archive copy implemented |
| Archive/deletion | No archive; approved encrypted archive; deletion under controlled maintenance | Decide purpose, access, legal holds, backup expiry and restore handling first; record retention operations by IDs/counts/policy version | Privacy/legal/product/security and DB owner (main 03) | Immutable application rows; dedicated controlled retention mechanism after approval, not an unrestricted delete API |

No legal duty or acceptable retention duration is asserted here. Indefinite retention is
not an approved fallback. Since persistence has not begun, waiting does not create a new
unbounded audit store. Existing local audit retention is unchanged and must be assessed
by its owners separately.

## Proposed technical contract and examples — NOT IMPLEMENTED

Use producer-owned stable event IDs with explicit source/type/version and occurredAt.
Record receivedAt separately. Retain the approved correlation ID only after bounded format
validation. Append-only application access; uniqueness on approved producer/event identity
makes replay idempotent. An event ID reused with conflicting content must be observable
and cannot silently overwrite the original. Outage recovery must not acknowledge before
storage; malformed metadata records use safe categories/position, never a raw payload log.

Candidate gateway request (reserved, currently unavailable):

```http
GET /api/v1/admin/audit-logs?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z&eventType=delivery.assigned&size=25
Authorization: Bearer <securely supplied Dispatcher token>
```

Illustrative redacted response shape, pending schema approval:

```json
{"items":[{"eventId":"11111111-1111-4111-8111-111111111111","eventType":"delivery.assigned","source":"S3","version":1,"resourceId":"22222222-2222-4222-8222-222222222222","occurredAt":"2026-11-01T10:00:00Z","metadata":{"action":"ASSIGNED"}}],"nextCursor":null}
```

Actor fields are intentionally absent until approved; actor filtering cannot ship while
actor storage/access is unresolved. Proposed errors follow endpoint-specific conventions:
401 unauthenticated; 403 unauthorized role/permission; 400 malformed or unbounded query.
No existing route/error contract is changed by this proposal.

## Security, operations, testing and rollback gates

After approval, implement S5 migration/controller/DTO/service and explicit producer APIs or
events through their owners. No edits to applied migrations. Test PostgreSQL append-only
behavior including TRUNCATE, duplicate/conflicting IDs, deterministic pagination with equal
timestamps and late records, all filters, invalid input, malformed/versioned ingestion,
redaction before storage, and approved retention/restore/hold behavior. Verify gateway
routing plus unauthenticated, CUSTOMER, MERCHANT, COURIER, SERVICE and DISPATCHER cases;
permission-qualified Dispatcher cases depend on the approved identity contract.

Operational approval must include ingestion lag/backlog, rejection categories, retention
operation evidence and owner escalation. No new collector/dashboard/threshold is installed.
A future rollback should disable ingestion/read availability without dropping evidence;
retention operations require an approved recovery/restore procedure. This proposal itself
is documentation-only and can be reverted without database or API effects.

Validation: inspected source paths, issue #348 and comments; no runtime behavior changed,
no migration/event/OpenAPI artifact created and no implementation tests claimed. Required
implementation tests remain blocked. The G17/full-backend evidence is a separate workstream.
