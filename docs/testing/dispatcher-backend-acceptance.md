# Issue #354: dispatcher backend acceptance

G02 is partially completed. Reassignment now repeats the existing active/account
and service-area checks and rejects a missing or mismatched parcel from the source
before looking up a replacement courier. It retains existing error codes, actor
attribution, role/permission checks, assignment transactions, audit and outbox.
No area-matching policy or new permission is introduced.

`DispatchServiceTest` adds negative reassignment cases and verifies that the actual
`UnavailableParcelSource` prevents queue, candidate, assignment, reassignment, and
cancellation work without writes. Production integration remains fail-closed.

`AssignmentPostgresIntegrationTest` adds:

- Different parcels, one courier, two independent services/repositories, with a
  barrier after each real PostgreSQL count: both pass a configured limit of one
  and insert. This characterizes the existing race, not correct capacity behavior.
- Cancellation in each proof completion state: the FK rejects deletion; assignment,
  evidence, audit and outbox remain unchanged after rollback.
- Reassignment in each proof state: original evidence/actor stay immutable and one
  additional audit/outbox record is written.

`OutboxPublisherTest` adds failed-send recovery through a new publisher and verifies
stable event ID, key and payload. Repository, Kafka and transaction manager are
doubles; this does not establish durable Kafka delivery or consumer deduplication.

## Decisions still required

| Dependency | Required decision / owner |
|---|---|
| Capacity | Code defaults to ten lifetime assignment rows and checks before insert; workload documentation says no capacity threshold is configured. S3 owner Nilakshana23 and reviewer pavi3alnx must approve the counted states, threshold, completed-proof treatment and same-courier replay semantics before moving checks/adding courier locks. No new capacity definition is inferred. |
| Cancellation after proof | Current FK failure reaches the generic persistence handler. S3 owner/reviewer must approve conflict response versus retained inactive assignment/history. No deletion, cascade, migration, or terminal-state rule is invented. |
| Reassignment after proof | Evidence survives but follows the parcel's current assignment for reads. S3 owner/reviewer must approve late reassignment and prior-evidence visibility. |
| S2 source | #353 with #351: authenticated candidate enumeration, authoritative eligibility, guarded claim, release and crash recovery. See the local [S2 characterization](../api/zd-353-dispatch-source-characterization.md). A parcel-detail GET is insufficient. |

G36 is blocked: `DispatcherAssignmentIT` is deliberately not created until the
production source exists. Existing HTTP/PostgreSQL and signed-JWT suites cover
isolated authorization, availability, single-parcel conflict and ownership. They
do not prove booking-to-queue success. No runtime adapter or direct assignment SQL
has been added to the real E2E module.

See [verification evidence](issue-354-implementation.md) for actual results.

Follow-up: Docker Desktop now exposes Engine 29.8.1. PostgreSQL startup remains
unverified: the selected-suite retry failed before container setup because S3's
generated classes contain unresolved-type compiler stubs. Native-memory failures
also occurred. The user has placed further tests on hold pending manual memory
cleanup. Clean S3 compilation and the PostgreSQL acceptance rerun remain required;
no API compatibility, shared infrastructure or business-policy change was made.

Latest resumption: clean reactor compilation resolved the invalid generated
dependency output. `AssignmentPostgresIntegrationTest` now passes **27/27** with
`-Ddocker.api.version=1.56` for this Docker 29.8.1 host. The default API 1.44 run
timed out during PostgreSQL readiness; no permanent API default was changed.
This confirms the existing cross-parcel capacity race and after-proof transaction
characterizations; it does not approve or implement a new capacity/cancellation
policy. The subsequent proof suite hit native-memory exhaustion, so further
verification stopped. HTTP and gateway suites remain pending; see the report.

Current completion audit: the proof submission/reassignment ownership race is now
fixed and its PostgreSQL suite passes 30/30. Submission locks the current owner
through evidence commit; this does not change post-commit assignment/cancellation
policies. The assignment repository and capacity policy were not changed, and its
27/27 result was retained without rerun. Atomic capacity enforcement remains an
unresolved acceptance criterion, not an implemented fix: the existing test proves
overbooking. The exact conflict between code's default limit and documented
unconfigured capacity still needs owner/reviewer resolution. See the final report
section for current HTTP/security results and production E2E blockers.
