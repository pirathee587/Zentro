# Backend independent security sign-off — Issue #359

Status: PARTIAL. Independent decision: UNVERIFIED; reviewer @Niro0526.
Main 10 cannot sign its own independent acceptance.

Current committed-fixture execution (`311ac2b`): 19 tests, 13 passes, five receipt
gateway failures and one tracking hydration timeout, zero skips. Exact role/status
observations are retained in [safe results](../testing/evidence/359-current-results.json).
The complete security requirement remains PARTIAL despite the passing token negatives.

`BackendRoleAcceptanceIT` uses actual Keycloak signatures and separate gateway /
service JWT validators. It does not mock a decoder. Fixture clients receive the
existing CUSTOMER, MERCHANT, COURIER, DISPATCHER and SERVICE realm roles; no ADMIN
application role is introduced. Keycloak administration remains fixture setup only.

| Endpoint / scenario | Expected | Evidence / remaining limitation |
|---|---|---|
| Gateway and direct S2 GET `/api/v1/parcels/{id}/receipt`, unknown ID | CUSTOMER/MERCHANT/DISPATCHER/SERVICE 404; COURIER 403; anonymous 401 | New signed-role matrix emits expected/actual statuses. Gateway routing gap must not be relabeled success. |
| Direct S2 receipt, real owner booking | Owner 200, foreign merchant 404 | Real gateway booking plus S2 source recount; no business-row seed. |
| Gateway receipt, real owner booking | 200 | BLOCKED: current gateway deny-all parcel read rule. Main 02 request R04. |
| Forged signature, gateway and S1–S5 protected routes | 401 | Signature byte mutation; every service independently checked. |
| Real wrong-audience token, gateway and S2 | 401 | Actual Keycloak client with audience mapper removed; no unsigned payload manipulation. |
| Foreign issuer token, gateway and S2 | 401 | Keycloak master-realm JWT is rejected; this checks foreign-realm rejection, not isolated issuer validation with the same signing key. |
| Expired Keycloak token, gateway and S2 | 401 after configured validator clock skew | Real one-second client token; bounded wait, without changing service validators. Current actual outcomes in evidence index. |
| Isolated issuer mismatch, interactive user login | Approved deny / approved flow | UNVERIFIED; existing domain JWT tests are supplemental only. |
| Disabled account / stale token after disablement | Approved disablement latency and deny semantics | BLOCKED on Main 01/02 policy and real canonical account setup, R06. Do not assume Keycloak disablement revokes previously issued stateless JWTs. |
| Courier progress/proof ownership and SERVICE transition restrictions | Owner-approved role/state contract | BLOCKED G03/G31/G30; SERVICE must not gain dispatcher/courier authority. Existing S3 tests do not establish full live acceptance. |
| Receipt PDF / private tracking / reports / all remaining protected operations | Endpoint-specific owner/foreign/wrong-role/anonymous outcomes | UNVERIFIED until complete route matrix and canonical private tracking hydration are verified. |

Safe status lines begin `ACCEPTANCE` and exclude tokens, request bodies and
identity data. Exact actual outcomes and counts are in the current evidence index.
The imported realm grants CUSTOMER by default. Initial courier-only expectations
were invalid for a token also carrying CUSTOMER. The corrected shared fixture
removes inherited default mappings from its own test accounts and asserts exactly
one application role before use; it does not change realm or production policy.
The completion client likewise has only SERVICE application authority.
Gateway/direct-service mismatch, wrong policy, skipped scenario or missing role
case prevents DONE. Reviewer must inspect all rows, current SHA CI, disabled-user
and SERVICE tests, remaining route inventory and upstream owner approvals before
recording a signed-off decision.

## Complete inventory versus verified live coverage

[REST route coverage inventory](../testing/evidence/359-route-acceptance-matrix.csv)
contains 64 unique method/path operations: 62 checked-in OpenAPI operations plus
the two implemented S2 receipt reads absent from the shared OpenAPI generator.
Its role cells describe coverage, not permission grants. UNVERIFIED is never a
claim that a role should be allowed or denied. Aggregate endpoint sign-off stays
PARTIAL until all applicable positive/negative/ownership cases are accepted.

Additional transport boundaries are outside that REST inventory:

| Surface | Existing implementation / contract | Live acceptance gap / owner |
|---|---|---|
| GraphQL `publicTracking` | S4 read-only schema/query handler | Canonical public read model is missing for new real bookings; gateway `/graphql` is not an approved exposed route. Main 08/02; UNVERIFIED. |
| GraphQL `delivery` / `myDeliveries` | S4 ownership-aware read queries | Exact owner, foreign owner, wrong role and signed-token live matrix required; private hydration and routing approvals pending. Main 08/02; UNVERIFIED. |
| GraphQL `deliveries` | Dispatcher-restricted read query | Existing domain tests are supplemental, not live Main 10 sign-off. Main 08/02; UNVERIFIED. |
| Identity gRPC `GetMerchantStatus` / `GetCourierProfile` | Implemented S1 service and shared JWT/internal-token interceptor | Shared five-service fixture disables the S1 gRPC listener; do not claim its health proves gRPC readiness. Preserve existing domain gRPC tests; Main 01/02 live acceptance remains required. |
| Parcel gRPC `GetParcel` | Proto declaration only in inspected source | No production server implementation found; must not treat it as a usable dispatch source. Main 04/02; BLOCKED. |
| Tracking events JSON versus SSE / explicit stream | JSON public reads and owner-authorized authenticated SSE differ | Content negotiation, expiry/reconnect and exact ownership need real initialized tracking rows; Main 08/02; UNVERIFIED. |
| Health / Prometheus / docs paths | Existing service/gateway management security configuration | Six health endpoints are tested. Health is not authorization sign-off for Prometheus/docs or upstream management exposure; Main 02/03 review required. |

No application ADMIN role/privilege predicate was found by the source scan for
`ROLE_ADMIN`, `hasRole('ADMIN')`, `"ADMIN"` or `DISPATCHER_ADMIN` in backend production
sources and the realm template. Keycloak bootstrap administration remains isolated
fixture administration, not a fifth application role. This source scan is
supplemental evidence and does not replace the remaining runtime matrix.
