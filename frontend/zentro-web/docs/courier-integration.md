# Courier portal integration

The courier portal uses the shared Axios client and TanStack Query keys through the API Gateway.
The existing delivery/proof integration is retained; #154 adds access-denial cleanup, assignment
polling, session cache cleanup, safe field-error mapping, and regression/ownership verification.
Backend production code, gateway rules, authentication and API/event contracts are unchanged.

## Calls and ownership

| Feature | Before / after transport | Endpoint | Gateway target |
|---|---|---|---|
| Assigned list | Real / real, now polls every 15 seconds | GET `/api/v1/courier/deliveries` | S3 |
| Detail | Real / real, neutral denied state and cache cleanup | GET `/api/v1/courier/deliveries/{parcelId}` | S3 |
| Proof retrieval | Real / real, denied evidence removed | GET `/api/v1/courier/deliveries/{parcelId}/proof` | S3 |
| Proof submission | Real / real, safe field-error messages | POST `/api/v1/courier/deliveries/{parcelId}/proof` | S3 |
| Profile | Real / real, unchanged | GET `/api/v1/users/courier-profile`; 404-only fallback GET `/api/v1/courier/profile` | S1 |
| Provision profile | Real / real, unchanged | POST `/api/v1/courier/profile/provision`; 404-only fallback POST `/api/v1/users/courier-profile/provision` | S1 |
| Personal information | Real / real, unchanged | PATCH `/api/v1/users/me` | S1 |
| Progress / failure | Unavailable / unavailable | No implemented courier endpoint | Follow-up |

There is no courier mock/real configuration switch in this checkout. Production courier APIs
already make real requests; MSW adapters remain available in tests. No mock deliveries are merged
into server responses. The gateway route table is checked by `deliveryApi.test.ts`, including both
profile fallbacks and explicit bearer headers. The profile route is matched before S3's broader
courier prefix.

The assignment endpoint returns **all assignments**, including older and completed assignments;
it does not implement a today window or supply parcel progress/addresses. The UI therefore says
“My assigned deliveries” and does not manufacture a local date filter or tracking data. Empty
results say “No deliveries are assigned to you yet.” Polling observes changes supported by the
server; it stops after 401/403. Loading, empty and error states are distinct. Access failures offer
no request Retry; 401 offers the existing staff sign-in flow.

| Caller / resource | Backend response | Frontend |
|---|---|---|
| Eligible assigned courier | 200; first proof POST 201, identical replay 200 | Assigned data / immutable proof |
| Another courier's assignment | 403 | `Delivery not available` |
| Unassigned parcel | 404 | `Delivery not available` |
| Own assignment without proof | GET proof 404 with `PROOF_NOT_FOUND` | Proof form |
| Non-courier human or SERVICE-only token | 403 | No delivery data |
| Anonymous / invalid token | 401 | Session-expired message and staff sign-in |

S3 derives the caller via S1 using the JWT bearer token, then matches both the canonical courier
profile ID and app-user ID against its own assignment. Both gateway and S3 require COURIER.
Detail/proof cache data is cleared on denied reads; the denied parcel is also removed from the
cached list. A denied detail never renders saved evidence or the parcel ID. The requested UUID
remains part of the route, but no response evidence is written to URL, browser storage or logs.

## Proof and completion

The implemented JSON contract requires `recipientName` (100 Unicode code points) and
`proofReference` (200); `notes` is optional (500). Values are trimmed, blank notes become null,
and NUL is rejected. The form mirrors these existing rules, including code-point counting.
Server field errors mark only known inputs with fixed wording; response text or submitted values
are never echoed in error messages. Duplicate clicks are guarded synchronously, and POST is
never retried automatically. Before another explicit attempt the form reconciles saved proof.
A 409 reconciles immutable evidence and retains conflict guidance; dependency/network errors
preserve entries for explicit retry. Saved proof invalidates the shared courier list and submitted
detail queries, preserving profile, other detail and other portal queries.

There is **no approved file-upload endpoint**, file size/type contract or storage URL workflow.
No file picker, EXIF processing, signature, location capture or invented upload call is added.
The implemented proof contract originates in merged #59 / PR #105; `docs/api/proof-of-delivery.md`
still describes field approval as pending, so this integration does not claim new contract approval.

The frontend cannot offer progress or failure transitions: S3 exposes no such commands and its
summary has no parcel-status field. It never calls dispatcher-only PATCH `/api/v1/parcels/{id}/status`.
Existing proof submission creates PENDING completion; S3's durable worker requests DELIVERED from
S2 with a SERVICE token. S2 permits SERVICE only `OUT_FOR_DELIVERY → DELIVERED`, plus an
idempotent DELIVERED repeat; other transitions are rejected. S2 publishes its existing status event
only for a changed status.
PENDING proof is polled every three seconds until COMPLETED, REJECTED or BLOCKED. Rejected/blocked
proof remains immutable and the UI directs the courier to dispatch. This existing worker is
separate from the missing Kafka assignment notifications described in #136.

## Cache isolation

Courier queries use the shared process-local principal scope. On account change, logout or portal
unmount, the courier layout cancels and removes queries belonging to its previous scope. Its
keyed content remounts drafts for the next account. In-flight requests use AbortSignal, and an
unmounted proof form cannot repopulate the previous account's query cache after a late POST.
No authentication/provider or global query-default changes are required.

## Verification boundaries

`GatewayCourierOwnershipIntegrationTest` uses the real gateway process, disposable Keycloak
(signatures, issuer and audience checked at both gateway and S3), real S3 HTTP/services/Flyway,
and S3's PostgreSQL database. S1 identity resolution is mocked; no S2 database is read. It verifies
caller-only lists, other-courier 403, unassigned 404, proof read/write denials, role denials,
forbidden dispatcher/status routes, and unchanged assignment/proof rows after rejected operations.
Existing S3 workflow, validation, worker and role tests remain in place. No nonexistent
progress/failure endpoint can be verified. Package the gateway before running the focused suite:

```sh
cd backend
mvn --batch-mode -pl api-gateway,dispatch-courier-service -am \
  -Dtest=GatewayCourierOwnershipIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test package
mvn --batch-mode test package
```

Frontend checks from the repository root, using Docker Node 22 when local Node is absent:

```sh
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm ci --no-audit --no-fund
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm test
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm run lint
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm run type-check
docker run --rm -v "$PWD:/repo" -w /repo/frontend/zentro-web node:22-bookworm-slim npm run build
```

Manual browser operation against a deployed stack, live deployment Keycloak/S1 identity resolution,
375px layout and light/dark rendering, real photo upload, production data and production credentials
have not been verified. Disposable Keycloak integration tests do not establish deployed configuration.

## Prepared follow-up issues

The #154 requester selected “Use existing APIs; document follow-ups.” Owners below are maintainers
verified from the owning source's git history, pending project assignment.

### S3/S2: Define courier progress and failure commands

- Owner: @pavi3alnx (S3 assignment source history); S2 contract reviewer to be assigned.
- Reproduction: `CourierDeliveryController` implements only GET list/detail/proof and POST proof.
  `DeliverySummary` has no parcel status. A courier's PATCH to the S2 public status route is denied
  by the gateway. There is no failure-reason request DTO or courier progress command.
- Gap: courier pickup/in-transit/failure actions cannot be integrated without a backend contract.
- Proposed next action: agree allowed transitions, ownership checks, failure-reason code-point and
  control/bidi validation, error/idempotency semantics and S3-to-S2 propagation. Implement and verify
  in a separate backend PR before enabling controls; preserve S2 status ownership.

### S3: Approve and implement proof-photo storage contract

- Owner: @Nilakshana23 (courier source history); evidence-contract reviewer to be assigned.
- Reproduction: `SubmitProofRequest` accepts only recipientName, notes and proofReference;
  `CourierDeliveryController` has no upload route.
- Gap: no file transfer, limits, storage reference validation or access/retention policy is defined.
- Options: approve an authorized object-storage upload/reference flow, or a validated multipart
  endpoint; retain text-reference evidence until the selected contract is implemented.
- Next action: resolve the pending evidence-field approval note and specify file count/type/size,
  access ownership, retention and any EXIF requirement before frontend photo work.

### S3: Define a today-filtered assignment contract if required

- Owner: @pavi3alnx (S3 assignment source history).
- Reproduction: `ProofRepository.deliveries` filters on courier IDs only, without date or terminal state.
- Gap: the current API cannot promise today's outstanding work.
- Next action: decide timezone, start/end boundaries and completed-assignment behavior before adding
  an optional filter and matching frontend label. Current all-assignment behavior is preserved.

## Actual validation — 2026-11-09

Base `develop`: `f452def60424e891786404d910e8248e8a285601`.
Frontend ran in Docker `node:22-bookworm-slim`, with the whole repository mounted so the route
inventory test could inspect the gateway YAML. Backend Maven ran on the host; PostgreSQL,
Keycloak and Kafka integration dependencies ran in Docker.

```text
npm ci --no-audit --no-fund: added 421 packages
npm test: Test Files 47 passed (47); Tests 388 passed (388)
npm run lint: 6 problems (0 errors, 6 warnings)
npm run type-check: exit 0
npm run build: built in 5.61s; exit 0
```

All six lint warnings are pre-existing in unchanged StatusChip, Toast, MerchantApprovalsPage
(two warnings), TrackingPages and AuthProvider files. New warnings: zero. Vite retains its
existing large-chunk warning; React Router emits future-flag notices in tests.

`cd backend && mvn --batch-mode test package` completed with `BUILD SUCCESS` in 09:42 minutes:

| Module | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| zentro-security-starter | 7 | 0 | 0 | 0 |
| api-gateway | 193 | 0 | 0 | 0 |
| identity-partner-service | 343 | 0 | 0 | 0 |
| parcel-pricing-service | 643 | 0 | 0 | 0 |
| dispatch-courier-service | 252 | 0 | 0 | 0 |
| tracking-reporting-service | 48 | 0 | 0 | 0 |
| notification-integration-service | 395 | 0 | 0 | 0 |
| Total | 1881 | 0 | 0 | 0 |

Docker-backed suites ran, including the new gateway ownership test (1 passed, 37.50 seconds
in the full build). The merchant-order-webhook-e2e module packaged successfully but its two
opt-in E2E tests were **NOT run**: its POM sets `skipTests=true` unless `-DrunE2E` is supplied.
These are not included in the 1881 executed tests or reported zero Surefire skips.

The first full backend attempt failed in the unchanged gateway concurrency test
`concurrentRequestsDoNotLeakAuthorizationOrIdentityBetweenRoles` (expected 200, received 504).
After frontend work finished, the full command was rerun without code/configuration changes;
all gateway tests and the remaining reactor passed. The first failed attempt is not counted
as a successful run. The focused ownership command also passed before the full build.

Actual runtime output:

```text
node -v: v22.23.4 (Docker)
npm -v: 10.9.9 (Docker)
java -version:
openjdk version "21.0.12.1" 2026-08-18
OpenJDK Runtime Environment (build 21.0.12.1+1-1-24.04.4-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.12.1+1-1-24.04.4-Ubuntu, mixed mode, sharing)
mvn -v:
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.12.1, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en, platform encoding: UTF-8
OS name: "linux", version: "7.0.0-42-generic", arch: "amd64", family: "unix"
docker --version: Docker version 29.8.0, build 88096ef
uname -sr: Linux 7.0.0-42-generic
```

Host Node and npm were absent; the requester authorized Docker execution. No host runtime was
installed. Manual/deployment limitations above remain unverified.

| Evidence item | Value |
|---|---|
| Merged commit SHA | to be recorded after merge |
