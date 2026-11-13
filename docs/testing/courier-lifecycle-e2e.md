# Issue #354: real courier lifecycle E2E

G31 and G36 are blocked on production prerequisites; no success scenario classes
or disabled placeholder tests are added. `CourierWorkflowIntegrationTest` uses real
S3 HTTP/PostgreSQL but doubles S1, S2, parcel source and JWT decoder.
`GatewayCourierOwnershipIntegrationTest` uses real gateway/Keycloak/S3/PostgreSQL,
but doubles S1 and seeds assignments directly. Neither is full courier lifecycle
acceptance. The E2E module currently reuses `DisposableGatewayStack`; shared support
has not been changed to simulate unavailable production integrations.

## Required dependencies

- #353 / #351: approved authenticated S2 candidate enumeration and guarded
  assignment lifecycle contract, implemented in production; a detail read does
  not guarantee eligibility through assignment commit.
- #354 S3 owner/reviewer with #353: courier progress endpoint, request/response,
  source states, idempotency and failure handoff approval. S2 is sole lifecycle
  writer; SERVICE may only complete successful delivery. COURIER cannot write S2
  status. See [failed-delivery blocker](../architecture/zd-057-failed-delivery-contract-blocker.md).
- #350: real eligible courier profiles and authenticated canonical identity;
  confidential S3 completion client provisioned with existing SERVICE permissions.
- #352: reachable Docker, service databases, Keycloak and Kafka; enabled outbox and
  projection consumers. #351 gateway routing and #357 S4 tracking initialization
  must be available without cross-service SQL or fabricated projection rows.

## Scenarios once prerequisites exist

`DispatcherAssignmentIT`: gateway booking appears in queue; eligible assignment
removes it; only exact courier sees it; audit/outbox agree; simultaneous assignment
has one winner; unauthorized roles, disabled couriers and upstream failure deny
without mutation.

`CourierLifecycleIT`: real booking and assignment; courier reads; approved progress
commands; proof submission; S2 DELIVERED and real tracking/history projection;
foreign courier denial, retry/restart and terminal conflicts. Progress/failure cases
must use the approved endpoints, not dispatcher impersonation or extra SERVICE
permissions. Only genuinely unavailable scenarios may be skipped with reasons.

No real lifecycle E2E command was run: the production source remains unavailable.
The latest focused proof PostgreSQL run succeeded (30/30) on Docker 29.8.1 using
the invocation-only API 1.56 override after a clean reactor compilation. This
verifies S3 persistence and ownership locking, not real S1/S2/S4 acceptance.
Memory and incremental-output limitations and the isolated HTTP/security results
are recorded in the implementation report. Earlier memory holds are historical;
the current instruction authorizes cautious, sequential verification.
Use the actual candidate SHA and the documented `-DrunE2E` command only when ready;
an uncommitted working tree is not a reproducible committed candidate.

See [verification evidence](issue-354-implementation.md).
