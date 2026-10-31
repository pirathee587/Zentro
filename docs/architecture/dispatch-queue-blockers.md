# Dispatch queue integration audit — 2026-10-31

S3 queue/assignment components are now implemented behind an internal parcel-source
port. The production parcel adapter still fails closed with 503 until the S2
contract and eligibility/lifecycle policy are confirmed. The live end-to-end flow
is **not complete**. No production deliveries or test-data runtime adapter exists.

## Repository and branch evidence

Fetched origin references without merging/checking out branches. Inspected all
available remote tips, including `feature-dispatcher`, `feature/65-admin-user-interface`,
`feature/18-delivery-booking-api`, `feature/61-delivery-history` and
`feature/19-Courier-profile`. Each has only DispatchCourierApplication.java in
dispatch main Java sources. Searches of backend main sources found no assignment
writer, parcel GetParcel implementation, candidate reader, Kafka consumer/producer
or outbox. History for dispatch/contracts points to scaffold commit `f497770`.
This does not cover unpublished/private work.

| Capability | File/API evidence | Why insufficient / usable |
| --- | --- | --- |
| Booking | Parcel `controller/ParcelBookingController.java`, `service/ParcelBookingService.java`: POST `/api/v1/parcels` | Returns one customer's new booking, not a dispatcher listing. |
| Source data | Parcel `service/ParcelBookingRepository.java`, migration `V2__create_parcel_booking.sql` | Stores tracking ref, sender/recipient address/city, creation date, status. Only insert and customer/idempotency lookup are implemented. No assignment fields. S3 cannot read this database. |
| Parcel RPC | `contracts/proto/parcel_pricing.proto` | GetParcel definition only, no server implementation. Returns ID/tracking/status; cannot enumerate or supply summaries/date. |
| Events | `contracts/events/parcel-created.schema.json`, `delivery-assigned.schema.json`, `event-envelope.schema.json` | Payload fields unspecified; no publisher, consumer or replay/backfill implementation. |
| Customer history | `frontend/zentro-web/src/portals/customer/services/config.ts`, `customerApi.ts` | Defaults to mock adapter, optional live endpoint is configurable. Customer-scoped mock/normalized rows are not authoritative dispatch data. Tracking backend is a scaffold. |
| S3 ownership | README service table, real-time flow, API overview | Already assigns dispatch and lifecycle/status updates to S3; documents GET `/api/v1/dispatch/unassigned`, POST `/api/v1/dispatch/assignments`. Their missing implementations belong to this task. |
| Identity directory | Identity `courier/AdminCourierController.java` | Existing GET `/api/v1/admin/couriers?active=true` and GET `/api/v1/admin/couriers/{id}` require DISPATCHER and can be reused. |
| Identity eligibility | Identity `courier/CourierProfileService.java`, `CourierProfile.java`, `grpc/IdentityPartnerGrpcService.java` | Registered profile active AND account ACTIVE. REST and RPC accept profile UUID or app-user UUID. No new Identity contract needed. |

## Completed S3 components

- New `dispatch`-owned Flyway `V1__dispatch_assignments.sql` (the first S3
  migration; no existing migration changed). Stores parcel, canonical courier
  profile/user UUIDs, assigning JWT subject and database-generated timestamp.
- `AssignmentRepository` performs a transaction with `INSERT ... ON CONFLICT
  (parcel_id) DO NOTHING RETURNING`. The database primary key arbitrates competing
  requests even across different service instances. A losing request returns 409;
  it never overwrites the winner. This initial-assignment scope has no release or
  reassignment operation.
- Dispatcher-only GET `/api/v1/dispatch/queue` (alias `/unassigned`), GET
  `/api/v1/dispatch/eligible-couriers?parcelId=<uuid>`, POST
  `/api/v1/dispatch/assignments` with `{parcelId,courierId}`. Input validation,
  trusted JWT subject and Problem Details codes cover validation, conflicts,
  missing resources and dependency/database failures.
- Queue excludes authoritative S3 assignment IDs before filtering; supports
  `search` (tracking/pickup/drop-off), inclusive UTC `from`/`to` dates, and
  `sort=newest|oldest`. No parcel-status or zone/vehicle rule is inferred.
- Queue response `{items,total,matchingTotal,complete}` only includes numeric
  totals for a complete reconciled source. Partial source responses use null
  totals; failures return errors, never a fabricated empty queue.
- React Query frontend displays rows, search/date/sort controls, loading, empty,
  no-match, partial-source and retry/refresh states. Overview card uses the same
  count contract. Accessible modal traps keyboard focus, restores focus, supports
  Escape, loads/refetches couriers, requires selection and prevents duplicate
  submissions. Success removes cached rows and refreshes counts/queries. Stale
  courier selection is cleared; delivery conflicts close the modal and refresh.
- `ParcelSource` is an internal S3 interface, **not an unapproved S2 endpoint**.
  Its list method supplies eligible parcels under the eventually agreed policy.
  Its guarded assignment callback requires eligibility through the write, using
  agreed lifecycle ownership or conditional claim/recovery. A remote detail read
  plus S3 locking alone is not a valid adapter. `UnavailableParcelSource` is the
  only production implementation and never invokes an assignment callback.

`IdentityCourierClient` reuses the existing REST directory and lookup, forwards
dispatcher bearer auth, checks effective active/account state, normalizes either
accepted UUID to the returned profile UUID, and bounds connect/read timeouts.
Missing resource, malformed dependency response, denied access, ineligible courier
and unavailable dependency fail closed. Nine mocked HTTP tests cover these paths.
It is wired into the assignment endpoint and revalidates immediately before the
transactional insert, inside the source eligibility callback. It does not claim
a distributed atomic guarantee over later Identity/S2 changes.

Canonical profile UUID is an S3 storage choice supported by the returned identity
data, not a service-owner blocker. Zone/vehicle fields are marked pending S3
approval; no matching restriction is inferred. Courier management stays in Identity.

## Genuine blocker 1 — enumerable authoritative parcel source

**Supplier:** S2 Parcel & Pricing owner. The table has the needed data, but no
implemented API or durable event exposes all bookings to S3. Existing GetParcel
cannot bootstrap a queue and is not implemented.

**Smallest proposed contract (not implemented/approved):** add ListDispatchParcels
to the existing parcel gRPC service, implement GetParcel, and extend ParcelResponse
with backward-compatible pickupSummary, dropoffSummary and createdAt fields.
Use authenticated S3/SERVICE or DISPATCHER calls (dispatcher bearer metadata is
already supported by Identity's gRPC interceptor). Example protobuf-JSON:

```json
{"pageSize":100,"pageToken":""}
```

```json
{
  "parcels":[{
    "parcelId":"11111111-1111-4111-8111-111111111111",
    "trackingNumber":"ZTEXAMPLE123",
    "status":"CREATED",
    "pickupSummary":"Colombo 03",
    "dropoffSummary":"Kandy",
    "createdAt":"2026-10-31T06:00:00Z"
  }],
  "nextPageToken":"opaque-token"
}
```

GetParcel request `{"parcelId":"11111111-1111-4111-8111-111111111111"}`
returns the same parcel shape; absent resource returns NOT_FOUND. Bound page size,
define deterministic complete traversal (including timestamp ties/concurrent
updates), and define summary fallback when city is absent. These are illustrative
values, not seed data. S2 lists bookings, not purportedly unassigned deliveries:
S3 excludes its own assignments. Page size is not queue total. S3 must fully
reconcile before returning a complete total, or omit it.

A read API is smaller than introducing messaging infrastructure. If the team
requires Kafka instead, S2 must define these payload fields and supply a durable
transactional publisher plus replay/existing-booking backfill. Topic names alone
and a feed containing only future bookings cannot populate an accurate queue.

**Exact question for S2 owner:** “Can S2 implement ListDispatchParcels and the
existing GetParcel RPC with these fields and complete pagination, authenticated
for S3/dispatchers? Please confirm summary fallbacks and snapshot semantics, or
point to an implemented equivalent. If Kafka is mandatory, who supplies the
transactional publisher and existing-booking backfill?”

## Genuine blocker 2 — initial eligibility and lifecycle agreement

**Owners:** S2 owner and S3 dispatch/domain owner together. README already makes
S3 the assignment/status owner. Storage, uniqueness and assignment endpoints are
our implementation work, not something another owner must build.

S2 creates CREATED bookings. Its enum/table constraint also allows PICKED_UP,
IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED and FAILED. No implemented dispatch policy
defines initial eligibility, retries, reassignment or concurrent cancellation.
CREATED cannot establish that there is no S3 assignment.

**Smallest proposed agreement:** S2 hands off bookings and S3 owns assignment and
subsequent lifecycle mutations. Confirm initial eligible state set (proposal:
CREATED only), always excluding S3 assignment records. FAILED retries/reassignment
remain outside this task unless specified. This proposal is not applied as policy.

Proposed S3 API uses README's existing path:

```http
POST /api/v1/dispatch/assignments
Authorization: Bearer <dispatcher token>
Content-Type: application/json

{"parcelId":"11111111-1111-4111-8111-111111111111","courierId":"22222222-2222-4222-8222-222222222222"}
```

```json
{"parcelId":"11111111-1111-4111-8111-111111111111","courierId":"22222222-2222-4222-8222-222222222222","assignedAt":"2026-10-31T06:05:00Z"}
```

Proposed 201 success; 409 Problem Details for already assigned/ineligible;
404 missing parcel/courier; 400 invalid request; 401/403 unauthorized; 503
dependency failure. Courier UUID in response is the canonical profile UUID.
An S3 transaction and database unique parcel assignment constraint protect
concurrent dispatchers; revalidate Identity immediately before persistence.
This is not a distributed atomic guarantee over subsequent Identity changes.

If S2 independently changes eligibility/cancels, lookup-then-insert is insufficient.
Then additionally propose an S2 idempotent conditional claim:
`ClaimForDispatch({parcelId,expectedVersion,assignmentId}) ->
{parcelId,version,claimed:true}`; stale/ineligible claims return
FAILED_PRECONDITION. S2/S3 must define recovery/reconciliation for a claimed
parcel whose S3 write fails. Do not add this complexity if S3 is the sole writer.

**Exact owner question:** “Please confirm S3 is sole assignment/delivery lifecycle
writer after booking and approve initial eligible states (proposal CREATED only,
always excluding existing S3 assignments). Can S2 or another service concurrently
cancel/change eligibility? If yes, who owns the conditional claim and recovery
contract? Are FAILED retries/reassignment required here or explicitly out of scope?”

## Integration remaining after S2 agreement

Implement the approved source adapter and any required reconciliation/claim
recovery, then run live contract and end-to-end verification. The S3 storage,
uniqueness, filtering, assignment and UI components listed above are implemented.
No shared S2 schema or endpoint was introduced. S3 runtime still needs its own
database/security environment; no other service's credentials were copied.

Migration setup: point S3 at its own configured database and start it; Flyway uses
schema/history `dispatch` and applies the new V1. The migration was only exercised
against isolated test PostgreSQL during development, not the configured live
Identity database. Existing Identity migrations are untouched.

## Runtime evidence and recovery

Initially no 8080/8083 listener, no gateway/Identity containers, and no backend JVM
(only the IDE Java server). Docker showed Keycloak on 8180 plus unrelated Zenova
containers. `docker logs zentro-api-gateway` returned “No such container”. Thus
the gateway was absent/stopped, not failing startup or listening on another port.
`backend/docker-compose.yml` only defines Identity and gateway, not S2/S3.
Dispatch has no local `.env`.

Attempted Compose startup, then stopped its image build before containers were
created after finding mixed host/container configuration. Identity `.env` uses
localhost Keycloak; gateway `.env` includes container issuer/upstream addresses.
Both files were preserved. Started the existing gateway JAR with process-only
localhost issuer and S1–S5 URI overrides, retaining every configured route.
Gateway health returned HTTP 200 UP. Logs are in ignored `.dispatch-runtime/`.
Unrelated containers/services were untouched.

Identity's normal target output also contained malformed gRPC constructor type
signatures (`javap` showed unqualified `GrpcSecurityInterceptor` and
`CourierProfileService`). That output failed test compilation and then runtime
class loading. Regenerating protobuf alone did not repair it. Built the current
sources in ignored `backend/identity-partner-service/target/runtime-build/`,
excluding the existing target output. The isolated production build passed and
`javap` confirmed fully qualified constructor types; source and IDE configuration
were unchanged. Identity is launched from that artifact with its original service
directory/configuration. Its first startup applied the **existing V4 Identity
courier-profile migration** to its configured database (v3 -> v4); no new migration
was authored. See `identity.out.log`/`identity.err.log` in `.dispatch-runtime/`.

## Verification and final state

- **Backend tests (`dispatch-courier-service`)**:
  - Total 39 tests executed, 39 passed, 0 failures, 0 errors:
    - `DispatchQueueControllerTest`: 14 passed (authorization, unassigned queue aliases, eligible couriers, assignment validations, Problem Details).
    - `DispatchServiceTest`: 13 passed (search/date filters, sorting, assignment conflict, Identity verification flow, unavailable parcel source handling).
    - `IdentityCourierClientTest`: 9 passed (token forwarding, courier eligibility, profile ID mapping, timeout/error handling).
    - `AssignmentPostgresIntegrationTest`: 3 passed (concurrency arbitration with `CyclicBarrier` across competing services, database-level unique constraint audit, transaction rollback on failure) using Testcontainers and Flyway against PostgreSQL 16 on Docker Desktop. Note: in sandboxed environments lacking Docker pipe access, Testcontainers fails environment initialization; when run with Docker pipe access, all 3 tests pass cleanly without modifications.
- **Sign In root cause and end-to-end verification**:
  - Root cause: In `UserProfileService.java`, `rs.getString("merchant_status")` returned `null` for non-merchants (including dispatchers), causing an unhandled `NullPointerException` on `.isBlank()` when fetching `/api/v1/users/me`. Keycloak also enforced `verifyEmail: true` on seed accounts, and `login.ftl` required correct error message mapping.
  - Verified live against Keycloak (8180) and Gateway (8080):
    - Login redirect: GET authorization endpoint returns 200 login view.
    - Keycloak authentication: POST credentials returns 302 redirect with authorization code.
    - Callback: `/openid-connect/token` returns 200 with valid ID token and access token containing `DISPATCHER` role in `realm_access`.
    - Token/session: Gateway successfully validates JWT with Keycloak JWKS.
    - Authenticated API request: GET `/api/v1/users/me` returns 200 OK with dispatcher profile and roles `["DISPATCHER", "CUSTOMER"]`.
    - Unauthenticated protected requests: GET `/api/v1/users/me` and `/api/v1/admin/couriers` return 401 Unauthorized.
    - Dispatcher role authorization: GET `/api/v1/admin/couriers` returns 200 OK for dispatcher token, and returns 403 Forbidden for courier token.
- **Frontend verification (`zentro-web`)**:
  - Dispatcher tests: 17 passed across `DispatchQueuePage.test.tsx` (14 tests) and `CouriersPage.test.tsx` (3 tests).
  - TypeScript typecheck (`npm run typecheck`): 0 errors.
  - ESLint (`npm run lint`): 0 errors (3 non-blocking warnings).
  - Production build (`npm run build`): Completed successfully in ~10.55s.
- **Authoritative parcel source status**:
  - Checked `parcel-pricing-service` (S2); no listing, enumeration, or dispatch query endpoint exists.
  - S3 `ParcelSource` internal port and `UnavailableParcelSource` fail-closed (503) implementation are preserved without faking the S2 contract.
- **Clean workspace**:
  - All ad-hoc probe scripts and temporary test files (`scratch/` directory) have been removed.
- **Client configuration**:
  - `IDENTITY_BASE_URL` (default `http://localhost:8081`) configured and documented in `backend/dispatch-courier-service/.env.example`. S3 still requires its own database credentials before production deployment.
