# ZD-33 Booking API test report

Issue: #68. Service: S2 Parcel & Pricing (`backend/parcel-pricing-service`). Endpoint under test: `POST /api/v1/parcels`, contract in [parcel-booking.md](../api/parcel-booking.md).

## 1. Scope

The full booking path:
- authentication and authorization
- request validation
- the pricing dependency (`PricingService`, the same calculation the quotation endpoint uses)
- persistence of the parcel row
- the `parcel.created` outbox event
- tracking reference generation
- duplicate submissions (`Idempotency-Key`)
- failure handling (pricing errors and rejections, database errors)
- the first lifecycle step after booking

Out of scope: the frontend form (decision D1: backend tests only; form findings are listed as follow-ups), the quotation endpoint's own rules (#30), and Kafka publishing (#58, tested separately).

## 2. Environment

| Item | Value |
|---|---|
| Base commit | `develop` at `efd12e2` (PR #108) |
| Java | Temurin 21.0.12 |
| Maven | 3.9.16 |
| Spring Boot | 3.3.5 |
| Testcontainers | 1.19.8, image `postgres:16-alpine` (and `confluentinc/cp-kafka:7.6.1` for the existing outbox test) |
| Docker | Docker Desktop, Engine 29.8.0 |
| OS | Windows 10 Home 10.0.19045 |
| Database | A throwaway PostgreSQL container per test class. The shared database is never used, and the outbox publisher stays disabled. |

## 3. How to run

```bash
cd backend
# Booking service tests (Docker must be running for the integration tests; they are skipped otherwise)
mvn --batch-mode --no-transfer-progress -pl parcel-pricing-service -am test
# What CI runs (all modules)
mvn --batch-mode --no-transfer-progress test package
# Only the new ZD-33 classes
mvn --batch-mode --no-transfer-progress -pl parcel-pricing-service -am test \
  -Dtest='ParcelBookingValidationBoundaryTest,Booking*IntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

The coverage report is written to `backend/parcel-pricing-service/target/site/jacoco/index.html`, with `jacoco.csv` next to it.

## 4. Test inventory by acceptance criterion

New classes added for ZD-33 are marked **(new)**.

| Acceptance criterion | Tests |
|---|---|
| Successful booking returns the expected response and reference | `ParcelBookingControllerTest.customerCanBook`; `ParcelBookingIntegrationTest.bookingIsPersisted`; **(new)** `BookingPersistenceIntegrationTest.storedRowIsComplete`, `BookingSecurityIntegrationTest.customerTokenBooks` |
| Invalid input is covered | `ParcelBookingControllerTest.invalidFieldReturns422` / `missingParcelReturns422` / `malformedJsonReturns400` / `badEnumReturns400` / `invalidIdempotencyKeyReturns400`; **(new)** `ParcelBookingValidationBoundaryTest` (61 cases: weight 0.1/20 accepted and 0.09/20.01 rejected, phone separators, an 8-character address accepted and 7 rejected, every maximum length at max and max+1, missing sections and fields, unknown fields ignored, no personal data in 422 responses) |
| Pricing outages are covered | `ParcelBookingServiceTest.pricingFailureSavesNothing` / `pricingRejectionSavesNothing` / `missingFeeSavesNothing`; `ParcelBookingControllerTest.pricingUnavailableReturns503`; **(new)** `BookingPricingOutageIntegrationTest` (503 with no parcel and no event, 422 on rejection, unusable rule gives 503, fallback finding F2) |
| Fee consistency with the quotation | **(new)** `BookingQuoteConsistencyIntegrationTest` (7 parcels against the seeded rule, plus the 3-decimal weight that found defect D-1) |
| Duplicate submission behaviour is verified | `ParcelBookingServiceTest.idempotentReplayReturnsOriginal` / `concurrentDuplicateIsReplayed` / `idempotencyKeyIsScopedToCustomer`; `ParcelBookingIntegrationTest.idempotencyKeyStoresOneRow`; **(new)** `BookingDuplicateSubmissionIntegrationTest` (201 then 200 with one row and one event, 409 on a different body, 5 parallel requests give one booking, keys scoped per customer, no key means no de-duplication) |
| Persistence and status creation are verified | `ParcelBookingIntegrationTest.bookingIsPersisted`; **(new)** `BookingPersistenceIntegrationTest` (status CREATED, version 0, server-side fee, owner from the token, normalised fields, one schema-valid `parcel.created` event without personal data, no initial history row) and `BookingLifecycleStartIntegrationTest` (CREATED → PICKED_UP accepted, skipping rejected) |
| Tracking reference generation | `TrackingReferenceGeneratorTest`; `ParcelBookingServiceTest.trackingRefCollisionIsRetried` / `trackingRefCollisionGivesUpAfterThreeAttempts`; **(new)** `BookingPersistenceIntegrationTest.manyBookingsGetUniqueReferences` (200 bookings, 200 unique `ZT-` references) |
| Authentication, authorization and business rules | `ParcelBookingControllerTest.anonymousReturns401` / `nonCustomerRolesReturn403` / `customerComesFromTokenSubject`; **(new)** `BookingSecurityIntegrationTest` (real filter chain with bearer tokens: CUSTOMER 201, no token, invalid or non-bearer gives 401, MERCHANT/COURIER/DISPATCHER/SERVICE or no roles gives 403, roles matched case-insensitively) |
| Integration with approved contracts | `ParcelEventContractTest`; **(new)** `BookingPersistenceIntegrationTest.oneSchemaValidCreatedEvent` (stored event checked against `contracts/events/parcel-created.schema.json`) |
| Errors, conflicts and edge cases | All of the above, plus `ParcelBookingControllerTest.databaseFailureReturns503` and `idempotencyConflictReturns409` |

## 5. Results

`mvn --batch-mode --no-transfer-progress -pl parcel-pricing-service -am test` with Docker running: **399 tests, 0 failures, 0 errors, 0 skipped**, about 115 s of test time. That is 302 existing tests plus 97 new ones.

| Class | Tests | Failures | Skipped | Time (s) |
|---|---|---|---|---|
| **controller.ParcelBookingValidationBoundaryTest** (new) | 61 | 0 | 0 | 1.1 |
| **integration.BookingQuoteConsistencyIntegrationTest** (new) | 8 | 0 | 0 | 5.4 |
| **integration.BookingPersistenceIntegrationTest** (new) | 4 | 0 | 0 | 9.1 |
| **integration.BookingDuplicateSubmissionIntegrationTest** (new) | 5 | 0 | 0 | 14.6 |
| **integration.BookingPricingOutageIntegrationTest** (new) | 4 | 0 | 0 | 5.6 |
| **integration.BookingSecurityIntegrationTest** (new) | 12 | 0 | 0 | 5.2 |
| **integration.BookingLifecycleStartIntegrationTest** (new) | 3 | 0 | 0 | 7.0 |
| controller.ParcelBookingControllerTest | 26 | 0 | 0 | 1.3 |
| service.ParcelBookingServiceTest | 16 | 0 | 0 | 0.1 |
| integration.ParcelBookingIntegrationTest | 6 | 0 | 0 | 5.7 |
| service.TrackingReferenceGeneratorTest | 4 | 0 | 0 | 0.2 |
| contract.ParcelEventContractTest | 10 | 0 | 0 | 2.2 |
| ParcelPricingAuthorizationAndEndpointTests | 53 | 0 | 0 | 1.7 |
| Other S2 classes (pricing engine, pricing service, status, lifecycle, merchant orders, outbox) | 187 | 0 | 0 | 55.0 |

The full CI build (`mvn test package`, all modules) passes as well.

## 6. Coverage

JaCoCo 0.8.12 runs during the test phase of `parcel-pricing-service` only. It has no thresholds and the CI pipeline is unchanged.

| Booking class | Lines | Branches |
|---|---|---|
| `service.ParcelBookingService` | 126/129 | 21/26 |
| `controller.ParcelBookingController` | 13/14 | 11/14 |
| `repository.ParcelBookingRepository` | 64/67 | 9/16 |
| `error.BookingExceptionHandler` | 25/25 | 3/4 |
| `service.TrackingReferenceGenerator` | 10/10 | 2/2 |
| `dto.CreateParcelBookingRequest` (with `Contact`, `ParcelDetails`), `dto.ParcelBookingResponse`, `error.IdempotencyConflictException` | 18/18 | 0/0 |
| **Booking classes total** | **256/263 (97.3%)** | **46/62 (74.2%)** |
| Whole S2 module | 90.4% | 70.4% |

The uncovered branches are mostly null checks on optional columns in the repository's row mapping, and defensive checks for a missing token subject in the controller.

## 7. Defects found and fixes

| ID | Test | Symptom | Root cause | Owner | Fix |
|---|---|---|---|---|---|
| D-1 | `BookingQuoteConsistencyIntegrationTest.weightWithThreeDecimalsKeepsTheQuotedFee` | For 2.555 kg the quotation showed LKR 736.60 but the booking charged and stored LKR 737.20 | `ParcelBookingService` rounded the weight to two decimals (2.56) before pricing, while the quotation priced the weight as entered | @14Jannan | Commit `4064153` `fix(parcel): price bookings with the weight as entered`. The booking now prices the weight exactly as submitted; only the stored `weight_kg` is rounded to two decimals. The test passes and all other tests are unchanged. |

No other defects were found. Test-only mistakes that were corrected before committing, not product defects:
- The phone "not echoed" check overlapped with the example number in the standard hint text.
- One Mockito stub was set up in a way that called the decoder.

## 8. Findings, risks and follow-ups

| ID | Finding | Evidence | Owner | Next action |
|---|---|---|---|---|
| F2 | When the pricing rule cannot be read (database error), `PricingService.getActiveRule()` silently uses the built-in default rates instead of failing. A booking then succeeds at the default price, which can differ from the configured price. | `BookingPricingOutageIntegrationTest.unreadablePricingRuleFallsBackToDefaultRates`: configured rate LKR 720.00; with the rule unreadable, LKR 670.00 | @Jathuja | Decide whether this should return 503 like an unusable rule does |
| F3 | A new booking writes no initial `CREATED` row to `parcel.parcel_status_history`; history starts with the first status change | `BookingPersistenceIntegrationTest.bookingWritesNoInitialHistoryRow` | @14Jannan | Documented as current behaviour; add a row only if tracking history needs it (#31) |
| F4 | The Send parcel form always sends `sender.name = "Sender Contact"` and derives the category from the size. The estimate request sends no category or cities. The price is not affected today. | `SendParcelPage.tsx` (createDelivery / estimateFee) | @pirathee587 | Review in a form follow-up |
| F5 | The form accepts weights with more than two decimals. After D-1 the fee matches the quote, but the stored weight is rounded to two decimals. | D-1 | @pirathee587 | Optionally limit the weight input to two decimals |
| F6 | Any user who also has CUSTOMER (the realm's default role) can book, including couriers | `BookingSecurityIntegrationTest.customerWithAnotherRoleCanBook` | Realm owner | Confirm that this is intended |

## 9. Manual verification

To be completed after the UI and API check before review (STEP 13): results and screenshot names.
