# ZD-057 / #132 — Failed-delivery contract blocker

**Status:** Blocked pending S2/S3 owner approval  
**Scope:** S3 failed delivery and exception reasons  
**Production code changed:** None

## Verified current contract

- **Ownership boundary:** WARP assigns courier assignment and delivery coordination to
  S3, with `dispatch_courier_db`; S2 remains the parcel-status authority.
  See [WARP.md](../../WARP.md#53-s3--dispatch--courier-service) and
  [parcel-status.md](../api/parcel-status.md#parcel-status-api-zd-13).
- **Authentication and ownership:** S3 courier endpoints require a validated
  `COURIER` JWT and compare both the resolved courier profile ID and app-user ID
  with the local assignment. No dispatcher override exists.
  See [proof-of-delivery.md](../api/proof-of-delivery.md#authentication-and-ownership),
  `CourierDeliveryController` and `ProofOfDeliveryService`.
- **Status authority and transitions:** S2 is the only parcel-status writer.
  `FAILED` requires a trimmed, nonblank free-text reason of at most 200 characters.
  `PICKED_UP`, `IN_TRANSIT`, and `OUT_FOR_DELIVERY` may transition to `FAILED`;
  `DELIVERED` and `FAILED` are terminal.
  See [parcel-status.md](../api/parcel-status.md#lifecycle) and
  `ParcelLifecycle`.
- **Permission boundary:** `DISPATCHER` may perform valid S2 lifecycle
  transitions. `SERVICE` is limited to `OUT_FOR_DELIVERY -> DELIVERED`;
  `COURIER` cannot call the S2 status API. S3 currently exposes no failed-delivery
  endpoint or dispatcher failure command.
  See [parcel-status.md](../api/parcel-status.md#role-transition-matrix-zd-25),
  `ParcelStatusController`, and `DispatchQueueController`.
- **Events:** S2 publishes `parcel.status.updated`. For `FAILED`, the event
  contains `failureReason` as a string from 1–200 characters; no reason-code enum
  or evidence fields are defined.
  See [parcel-status-updated.schema.json](../../contracts/events/parcel-status-updated.schema.json).

## Missing approvals / blockers

| Missing contract | Why implementation is blocked | Responsible owner |
|---|---|---|
| Allowed failure-reason codes | No approved enum exists. The current contract is free text only; inventing codes would change behavior and validation. | S2 status owner with S3/product owner |
| Failed-delivery evidence schema | S3 POD fields are explicitly proposed and have no approved failure-evidence requirement. Do not make recipient name, notes, proof reference, uploads, signatures or location mandatory for failure reports. | S3 owner and reviewer; S2 owner if forwarded |
| S3 endpoint and request/response shape | Existing S3 endpoints cover assignment, delivery reads and successful POD only. No approved path, idempotency key, conflict response or error contract exists for a failure report. | S3 owner |
| State-transition authority | S2 permits `FAILED` for `DISPATCHER`, not `SERVICE` or `COURIER`. The intended handoff from a courier report to a dispatcher-authorized S2 transition is undefined. | S2 and S3 owners |
| Dispatcher permission | WARP lists permission examples, but no approved permission is mapped to a failed-delivery command. Do not reuse or invent `DISPATCH_*` permission semantics without approval. | S3 security owner |

## Required next actions

1. S2 owner confirms whether the existing free-text reason remains the complete
   contract or supplies an approved, versioned reason-code list.
2. S3 owner defines the endpoint, idempotency/conflict behavior and whether a
   failed report is only recorded in S3 or also requests an S2 transition.
3. S2/S3 owners define the dispatcher handoff, including the exact permission,
   allowed source states and terminal/replay behavior.
4. S3 owner confirms whether any existing POD evidence is optional for failure
   reports; no new mandatory evidence should be assumed.
5. After approval, add focused tests for valid, invalid, duplicate, conflicting,
   unauthorized and illegal-transition cases, then update only the affected API
   or event documentation.

Until these decisions are recorded against #132, implementing ZD-057 would
require inventing reason codes, evidence requirements, an endpoint, or a state
machine. This document intentionally makes no production or contract changes.

## Issue #354 revalidation (2026-11-13)

G03 remains blocked after checking the current `CourierDeliveryController`, S2
`ParcelStatusController`/`ParcelStatusService`, and the existing role-transition
matrix. No approved courier progress/failure endpoint or expanded SERVICE
authority is present. Existing successful POD completion is retained. Blank and
overlong failure-reason tests belong to S2's existing API until an S3 request
contract exists; inventing an S3 endpoint to test them would cross this boundary.
S2/S3 endpoint, idempotency, evidence and dispatcher-handoff approvals above are
still required. See [real lifecycle acceptance](../testing/courier-lifecycle-e2e.md)
and [Issue #354 verification](../testing/issue-354-implementation.md).
