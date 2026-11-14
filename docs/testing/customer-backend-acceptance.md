# Customer backend acceptance — G34 / Issue #359

Status: PARTIAL. This is the customer-specific scenario map; the single candidate
index and current execution results remain in [integration evidence](backend-integration-evidence.md).
It does not replace domain-owned booking, pricing, receipt or tracking implementation.

| Required scenario | Reused or new acceptance | Acceptance boundary / unresolved dependency |
|---|---|---|
| Successful booking, tracking reference, matching replay | Existing `BackendRoleAcceptanceIT.realBookingPersistsOnceAndReachesPublicTrackingWithoutBusinessSeeding` | Gateway/Keycloak/S2; assert 201 then 200, same booking, one source row and one created outbox fact. |
| Conflicting duplicate | New `CustomerBookingIT.conflictingReplayPreservesOriginalBookingAndSingleOutboxFact` | Real gateway request changed only in meaningful notes; 409 and independent S2 recount, original booking/event retained. |
| Invalid input, no partial writes | New `CustomerBookingIT.invalidBookingDoesNotPersist` | Existing validation contract: 422 for missing required fields, no persisted booking. |
| Transaction failure, retry/recovery | New `CustomerBookingIT.outboxStorageFailureRollsBackBookingAndSameKeyRecovers` | Disposable S2 outbox-insert fault; expected gateway 502/no booking (existing upstream-5xx sanitization; S2 handler uses 503), cleanup, identical key retries to 201 and one booking/event. Does not claim pricing outage coverage. |
| S1 identity and foreign profile | New `CustomerBookingIT.sameRoleForeignCustomerCannotReadProfileOrDirectReceiptIncludingPdf` | Synchronize owner through actual S1 `/users/me`, then another CUSTOMER cannot read profile. |
| Receipt JSON/PDF, same-role foreign customer | Same new customer test | S2 owner 200, foreign CUSTOMER 404; PDF signature checked without exporting its body. Gateway receipt defect remains separately asserted by existing role suite. |
| Initial CREATED history and public tracking | Existing role suite | Initial booking is an outbox fact, not a duplicate status-transition row. Existing public-read acceptance remains failing until Main 08 initializes the tracking parcel. |
| S4 restart | New independent role-suite restart test | Require one actually consumed CREATED event, restart the real S4 JVM, confirm health and unchanged event count. Does not seed a tracking parcel or establish private owner hydration. |
| Pricing outage | Existing domain pricing/fallback tests only | Live acceptance remains BLOCKED: pricing is in S2 and approved fallback behavior must not be replaced with an invented failure policy. Main 04 supplies the supported fault/acceptance boundary. |
| Private owner tracking / receipt through gateway | Existing failing acceptance / pending domain provider | Main 08 hydration and Main 02 route/OpenAPI changes required. Foreign-customer denial is not inferred from a universally unavailable route. |

The customer identities are real disposable Keycloak service accounts with exactly
CUSTOMER application authority. They exercise signature validation and S1 canonical
profile creation, but are not evidence for human interactive sign-in. No production
credentials, assignments, tracking parcels or projection rows are fabricated.

Fault injection creates a function/trigger only in the disposable S2-owned test
database and removes it in `finally`. No applied migration or production schema
file changes. Business requests still enter through the gateway; runtime services
do not query another service database. Fixture SQL is limited to owning-source
reconciliation and the explicitly named storage fault.

Policy #301 (controlled destination), #305 (same address), and #306 (dimensions
versus booking weight) remain open. Their inspected comments contain no approval
resolving these decisions. They are not bypassed by acceptance fixtures.
