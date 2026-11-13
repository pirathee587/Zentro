# Issue #354: proof validation and ownership

The focused PostgreSQL suite now passes **30/30** (2026-11-13), including validation
no-write assertions, both canonical owner IDs, stale ownership, immutable evidence,
identical/conflicting concurrent submissions, rollback, lease fencing and recovery.
See the [implementation report](issue-354-implementation.md) for actual commands,
intermediate failures and isolated HTTP/security results. These are S3 tests with
identity doubles, not live S1/S2 or complete lifecycle acceptance.

A verified submission race was corrected: reassignment could commit after the
service ownership lookup and before proof insertion. `ProofRepository.saveOwned`
now rechecks profile ID and app-user ID using `SELECT ... FOR SHARE` inside the
same transaction as evidence persistence. A reassignment that wins first causes
403 with no evidence write. An accepted proof holds the assignment lock through
commit, blocking reassignment/cancellation in that interval. The reverse-order
regression checks PostgreSQL lock-timeout SQLSTATE 55P03 and verifies that existing
post-commit reassignment behavior still preserves the original evidence.

The initial service ownership check remains for existing validation/error ordering.
Identity HTTP calls and request normalization run before the proof transaction;
no remote call is made while holding its database lock. Identical replay remains
200, first submission 201 and conflicting evidence 409. The database immutable
trigger and completion worker are unchanged. No migration or API shape changed.

The reference remains opaque: absent, blank, overlength or NUL-containing values
are invalid under the existing contract. No URL, filename, prefix, recipient
alphabet or reference-pattern restriction is approved. Existing tests cover Unicode
code-point boundaries, normalization and independently blank required fields.
Evidence-field approval remains pending in the original API documentation.

Existing `CourierDeliveryControllerTest` verifies normalized fields and both owner
IDs are forwarded to the transactional persistence method. Signed RS256/JWKS
coverage additionally denies proof reads/submissions when either canonical owner
ID differs. These security tests use repository and identity doubles; PostgreSQL
race tests exercise real transactions independently.

Reassignment after committed proof retains current visibility behavior and original
evidence/actor. Approval of late reassignment, cancellation conflict semantics and
prior-proof visibility remains separate; this correction does not invent those
policies. Real authenticated S1/S2 completion and tracking acceptance remain blocked
as described in [courier lifecycle E2E](courier-lifecycle-e2e.md).
