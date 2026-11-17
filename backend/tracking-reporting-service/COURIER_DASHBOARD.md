# Courier Today dashboard backend

`Query.courierTodayDashboard(page: 1, size: 10)` is an additive, courier-only read.
Existing GraphQL queries and REST delivery/proof operations are unchanged.

## Ownership and credentials

S4 verifies the incoming courier JWT and requires `COURIER` without `SERVICE`.
Additional human roles do not broaden the dashboard scope. It forwards the same
courier bearer JWT to S3 `GET /internal/courier-dashboard/assignments`. S3 independently
validates the JWT and requires `COURIER` without `SERVICE`. It forwards that JWT to
S1 `GET /internal/courier-dashboard/identity`, whose independent resource-server
validation and courier authorization protect a read-only self lookup. Only the
verified JWT subject is used; caller-supplied identity parameters are never used.
S1 returns canonical application-user and courier-profile UUIDs without provisioning.
The existing profile GET is deliberately not reused because it may create records.

S3 uses those two UUIDs in one read-only SELECT over its current assignment and
proof tables. S4 batch-loads only these canonical parcel UUIDs, never authorizing
with the asynchronous `courierSubject` projection. It obtains a second snapshot
before returning details; any parcel/assignment-timestamp change fails the read.
Authorization is a distributed read snapshot, not a guarantee of revocation after
the response is sent. No database transaction is held across the network calls.

S3's snapshot controller emits scoped, sanitized problem codes for courier
authentication, eligibility, missing profiles and unavailable storage. S4 recognizes
only these explicit codes as courier failures. An unclassified upstream 401/403/404
(including an undeployed snapshot route) remains a dependency failure rather than
being incorrectly attributed to the courier's session or profile.

## Runtime configuration

The dashboard requires no confidential client, token endpoint or service secret.
Existing SERVICE-protected proof/status operations and legacy S1 gRPC security are
unchanged. The dashboard-specific gRPC contract/provider have been removed.

Reuse the existing S3 `IDENTITY_BASE_URL=http://identity-partner-service:8081`.
Set S4 `ZENTRO_DASHBOARD_DISPATCH_BASE_URL=http://dispatch-courier-service:8083`
through the existing deployment mechanism. Compose already passes service `.env`
files through `env_file`. Localhost defaults are for JVM development, not Docker.
Issuer, audience and JWKS settings remain the existing resource-server settings.
Tokens are sent only to configured trusted service origins and are never logged.

Deployment is a separate safety gate: existing services enable Flyway, S3 has a
proof-completion worker, and S4 Kafka consumers persist projections. Recreating
these services against shared storage cannot be certified write-free. Obtain
explicit authorization or use an approved isolated environment before deployment.
Do not disable unrelated security or change shared database settings as a workaround.

## Population and totals

- The day is calculated at query start in `Asia/Colombo`.
- Include all currently assigned nonterminal deliveries, including unfinished carryover.
- Include a current terminal `DELIVERED`/`FAILED` projection only when its matching
  event timestamp is today, not in the future, and not before the current assignment.
  Events require a stored event ID. A stored `deliveredAt` is a fallback for delivered
  projections; there is no invented failure timestamp.
- Exclude terminal records without reliable qualifying timestamps. Counts describe
  the currently assigned population, not historical performance credited to a courier.
- Missing tracking projections remain explicit unresolved rows with
  `projectionAvailable: false`, null tracking details, and no invented status.
  They contribute to assigned/remaining counts but are not selected as current delivery.
- Totals cover the entire population before pagination. Remaining equals assigned
  minus confirmed delivered and failed. Proof `PENDING`, `REJECTED`, and `BLOCKED`
  are not parcel delivery/failure statuses.
- Current delivery prefers a known `OUT_FOR_DELIVERY` status, otherwise the oldest
  known nonterminal assignment, with canonical parcel UUID as the stable tie-breaker.
- Page numbers start at 1 and size is capped at 50. More than 5000 current assignments
  fails closed instead of returning silently truncated totals.

Parcel status is still an eventually consistent S4 projection. The authoritative
S3 snapshot establishes ownership, not instantaneous S2 status freshness. REST actions
must continue to enforce their own current-ownership checks using `parcelId`, never
the S4 tracking-row `id`.

## Verification status

The earlier offline-cache blocker was resolved by a final Maven run using the
declared versions and configured repositories, without version overrides. S1, S3
and S4 compiled and their selected unit/security regressions passed. A contract test
also executes the actual frontend operation against S4's schema and DTO mappings.
Dependency/build verification does not establish deployment readiness: deployment
configuration and authenticated end-to-end runtime behavior remain unverified.
No shared-database tests or authenticated requests to running services were performed.

## Event-only parcel projections

The isolated HTTP flow confirmed that parcel events create tracking events and
`tracking.delivery_projection`, but do not hydrate contact metadata in
`tracking.tracking_parcel`. Dashboard reads now batch-read the authoritative event
projection for owned parcel IDs missing a hydrated row. They return the real tracking
reference/status and leave unavailable recipient/weight/size fields null. This is a
read-only fallback, not read-time backfill or placeholder database insertion. Existing
tracking queries, event payloads, ownership rechecking and JWT forwarding are unchanged.
