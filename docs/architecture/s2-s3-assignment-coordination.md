# S2/S3 assignment coordination

## Scope and eligibility

S2 owns parcel lifecycle and claim state. S3 owns assignments, audit and assignment
outbox events. This is a recoverable protocol, not a global database transaction.

No assignable state set was specified by the existing business contract. Initial
assignment and candidate enumeration fail closed until S2 is explicitly configured
with `ZENTRO_ASSIGNMENT_ELIGIBLE_STATUSES` (comma-separated canonical ParcelStatus
names). Unknown and terminal states are rejected at startup. CREATED is never
implicitly assignable. Test fixtures explicitly configure PICKED_UP; that is not a
production policy decision. Existing-assignment operations conservatively permit
only nonterminal parcels, retaining the existing S3 proof/ownership protections.

Candidates use one SQL snapshot, stable creation-time/UUID ordering, and a 5000-row
bound. More rows return complete=false, which the existing S3 queue treats as
unavailable rather than fabricating a complete population. DTOs contain UUID,
tracking reference, timestamps and city-level service areas, not contact details.

## Protocol and fencing

1. S3 durably commits a PREPARED operation identity before any remote acquisition.
2. S3 locks that operation row in its assignment transaction. S2 acquisition verifies
   the durable PREPARED identity with S3, locks the parcel row, validates eligibility,
   and writes HELD. One partial unique index permits only one HELD claim per parcel.
3. S3 performs existing courier/service-area/workload checks and persists assignment,
   audit, outbox and COMMITTED outcome in the same local transaction. Existing
   repository TransactionTemplates join this transaction.
4. S2 confirmation verifies COMMITTED with S3 before changing HELD to CONFIRMED.
5. On errors S3 obtains the same operation row lock, changing PREPARED to ABORTED
   only after any live transaction finishes. A committed operation remains COMMITTED.
   If storage cannot determine the outcome, no claim is released.
6. S2 release verifies durable ABORTED. A release arriving before a delayed acquire
   creates a RELEASED tombstone; that operation can never acquire afterward.
   Tombstones also cover missing parcel UUIDs; the coordination table deliberately
   has no parcel foreign key, while HELD acquisition always locks/verifies the parcel.

The operation row lock is the fence: recovery cannot abort while an assignment
callback holds it, and an aborted operation cannot execute a later callback. Claims
have no automatic expiration. Confirmation/release are repeatable only for the same
operation, parcel, owner and mode. A timeout does not prove rollback.

S2 status transitions retain their existing rules and history/outbox payloads. The
existing status update now additionally rejects HELD claims with retryable HTTP 503
(via the existing status error handler), while holding the same
parcel row lock used by acquisition. This avoids misclassifying a temporary claim as
a definitive proof-completion rejection (the existing worker treats 409 as rejected).
Status changes after confirmed assignment
continue normally. An idempotent no-change status request remains a no-op.

## Endpoints (internal, direct service URLs)

S2 `/internal/assignment-claims`:
- GET `/candidates` and `/candidates/{parcel}`: DISPATCHER + DISPATCH_VIEW; no claims or writes.
- POST `/{operation}/acquire`, `/confirm`, `/release`: canonical parcelId and mode
  ASSIGN or ASSIGNED. DISPATCHER; ASSIGN additionally requires DISPATCH_ASSIGN.
- GET `/{operation}`: claim state, restricted to original dispatcher.

S3 `/internal/dispatch/assignment-operations`:
- GET: up to 100 unresolved operations for the authenticated dispatcher.
- GET `/{operation}`: durable outcome, restricted to original dispatcher; initial
  assignment operations additionally require DISPATCH_ASSIGN.
- POST `/{operation}/reconcile`: locks/fences unresolved work, then confirms/releases
  according to durable outcome. Same dispatcher/permission boundary as outcome reads.

The S2 outcome callback is necessary: a caller cannot release an in-flight claim
merely by submitting an unauthenticated assertion that S3 rolled back. It uses a
trusted configured S3 origin and the forwarded dispatcher JWT, never a caller URL.
Reassignment/cancellation retain their existing dispatcher role boundary; this
feature does not silently introduce different permission requirements for them.

## Recovery and authentication

No new Keycloak client, shared secret, background worker or token persistence is
introduced. Reconciliation runs on explicit dispatcher requests or a retry for the
same parcel, including before the original assignment duplicate check. The original
dispatcher may log in again and reconcile using a fresh token with the same subject.
If unavailable, claims intentionally remain held; operational recovery authorization
must be addressed separately rather than using another user's credentials.

A successful local commit followed by failed confirmation returns an error, not a
false rollback. Retry reconciles it first; duplicate assignment retains the existing
409 behavior without another audit/event. This does not replay a lost HTTP success
body. No cleanup deletes operation identities or release tombstones automatically.

## Deployment prerequisites (not executed)

- Review/apply S2 V10 and S3 V5 migrations through an approved deployment separately.
- S2 `ZENTRO_ASSIGNMENT_DISPATCH_BASE_URL=http://dispatch-courier-service:8083`.
- S3 reuses `PARCEL_BASE_URL=http://parcel-pricing-service:8082`.
- Configure the approved S2 assignable-state set. Empty is intentionally unavailable.
- Existing JWT issuer/audience/JWKS settings and dispatcher permissions remain intact.
- Direct HTTP calls require trusted internal networks; existing plaintext transport
  is unchanged. Do not expose internal routes through the gateway for convenience.

Existing SERVICE proof completion and Courier Today JWT-forwarding reads are unchanged.
The pre-existing cross-parcel workload-limit race is not repaired by parcel claims.
Separate S2/S3 transactions are not globally atomic; durable unresolved claims and
explicit reconciliation favor safety over automatic availability after failures.

The outcome callback requires a spare S3 HTTP thread and database connection while
the write transaction is active. Pool exhaustion fails closed through bounded HTTP
and transaction timeouts; deploy with adequate connection/request concurrency.
