# Issue #359 owner implementation and approval requests

Status: BLOCKED. Reconciled against `1f1b766d00f18c8eae788a1f399b34fb88e1bb0c`,
2026-11-14. This document requests decisions; it grants none.
Issues #350–#354 are closed with no comments returned at inspection. Their
closure does not resolve the source/contract gaps below. Main 10 does not reopen
or reassign them. Coordinate follow-up through each named owner's branch/PR.

| Request | Primary implementation owner | Required decision and concrete implementation | Objective acceptance / merge dependency |
|---|---|---|---|
| R01: dispatch source, G02/G36 | @Jathuja, Main 04 #353 | With Main 02/05 approve authenticated candidate enumeration, eligible state set, completeness/pagination, version/stale claim semantics, assigned lookup and claim/release/recovery. Implement S2 provider on an owner branch. Detail GET is present but cannot supply these guarantees. | Real gateway booking appears as candidate; stale/ineligible claim denies; failed S3 persistence has approved reconciliation. Main 05 adapter follows provider and contract. |
| R02: courier progress, G03/G31 | @Nilakshana23, Main 05 #354 | With @Jathuja/@pavi3alnx and authorized product/security approver, record paths/request/response, source/target states, actor/assignment IDs, reason/evidence requirements, retry key/conflict handling and dispatcher handoff. S2 remains status/history/event authority. Implement only after approval. | Owner/foreign courier, wrong role, illegal transition, blank/overlong reason, identical/conflicting retry, downstream failure and atomic history/outbox tests; real lifecycle scenario by Main 05. |
| R03: capacity and late proof, G02 | @Nilakshana23, Main 05 #354 | Product/reviewer must decide counted active states, threshold/default, completed-proof counting, reassignment treatment, late cancellation conflict and retained evidence visibility. Then implement courier-scoped transactional capacity for initial/reassignment writes with consistent lock order. | Capacity=1, two instances/different parcels/barrier: one success, approved conflict, one counted assignment and matching audit/outbox. Preserve old race characterization until replaced by owner-approved regression. Proof cancellation preserves immutable evidence and rolls back on rejection. |
| R04: gateway receipt/detail, G33/G34 | @pavi3alnx, Main 02 #351 | S2 has detail and JSON/PDF receipt controllers; gateway currently denies `/api/v1/parcels/**` except explicit POST/PATCH/estimate. Confirm external receipt/detail authority; register approved paths and matching OpenAPI. Preserve owner-scoped S2 enforcement. | Real signed owner receipt 200, foreign 404, courier 403, anonymous 401 at both gateway and S2; forged/expired/issuer/audience negatives. Main 10 regression must stay failing until approved routing lands. |
| R05: ownership hydration, G31/G34 | @hephzibah-peries, Main 08 #357 | With Main 02/04 agree how private tracking obtains canonical customer/courier identity. Current event schema excludes those identities and consumer rejects extra fields; do not backfill with cross-service SQL or mutate schema without approval. | Real booking projects once and is visible to exact owner; foreign customer denied. Event replay/reorder and receiver restart do not duplicate initial/terminal history. |
| R06: live disable and signed claims, G33 | @kirusigan03, Main 01 #350 | With Main 02 security approver define disablement latency for already-issued tokens, account-status enforcement points and SERVICE restrictions. Provide approved real user/courier provisioning and completion credentials. | Disabled/stale-token matrix across gateway/services with expected versus actual statuses. Main 10 cannot approve JWT revocation policy. |
| R07: clean runtime/recovery/release, G39/G41 | @Niro0526, Main 03 #352 | Review disposable stack, exact toolchain/images, broker restart/offset replay and per-service backup/restore drill. Authorized release decision-maker must approve version/tag scope, residual risks and deployment/rollback policy; identity of that approver is not established by this audit. | Reviewer reproduces clean start and restore/recovery; candidate SHA CI passes; independent security/recovery sign-off and explicit policy approval before release. |

Main 05 owns `CourierLifecycleIT` and `DispatcherAssignmentIT`. Main 10 supplies
`BackendAcceptanceStack`, generic role/ownership acceptance and sanitized evidence.
No competing domain scenario or production adapter is introduced here.

Approval record required for each decision: decision text, approver with authority,
issue/comment URL, timestamp, contract revision, implementing PR and negative-test
evidence. No approval record was found in the inspected comments.
