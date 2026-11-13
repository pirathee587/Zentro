# [04/10] S2 Parcel Booking, Pricing and Dispatch Source — Characterization & Gap Audit Report (ZD-353)

**Issue:** [#353 — [04/10] Backend — Parcel Booking, Pricing and Dispatch Source](https://github.com/pirathee587/Zentro/issues/353)  
**Repository:** `pirathee587/Zentro`  
**Primary Assignee:** @Jathuja  
**Independent Reviewer:** @Kiruthiyan  
**Base Branch / Target:** `develop`  
**Base Commit SHA:** `897ff426c3123430b4e74ce2e8790fd8dad7335b`  
**Task Branch:** `feature/353-parcel-booking-pricing-and-dispatch-source`  
**Date:** 2026-11-13  

---

## 1. Executive Summary

This report establishes the baseline architecture, executable characterization evidence, and classification status for the consolidated S2 (`parcel-pricing-service`) scope assigned under issue **#353**.

In accordance with WARP.md architecture rules, database-per-service isolation, role constraints (no ADMIN role; CUSTOMER, MERCHANT, COURIER, DISPATCHER only), and the main issue instructions, this work package:
1. Re-verified all existing implementations against latest `origin/develop` (`897ff42`).
2. Confirmed that **G13 currency format validation** has already landed upstream (via PR #120 / `7e1e0d5`).
3. Confirmed that **G13 default fallback behavior** in `PricingService` and `ZoneService` on database outage is deliberate, documented, and protected by regression tests.
4. Characterized the remaining findings (**G01, G10, G11, G12, G43, X01**) using new dedicated executable regression/characterization tests (`Zd353EngineAndServiceCharacterizationTest`, `Zd353ControllerCharacterizationTest`).
5. Documented the exact approval owners, prerequisite decisions, and blockers for all dependent runtime sub-tasks without inventing unilateral business or pricing rules.

---

## 2. Coverage Matrix & Classification Status

| Identifier | Classification | Service | Historical Ref | Current Implementation State | Status / Verdict | Approval Owner |
|---|---|---|---|---|---|---|
| **G01** | MISSING / BLOCKED | S2 / S3 | [#346](https://github.com/pirathee587/Zentro/issues/346) | Authoritative parcels stored in `parcel.parcel`; `contracts/proto/parcel_pricing.proto` only defines `GetParcel`; S2 has no gRPC server; no candidate enumeration or guarded claim API exists. | **CHARACTERIZED — APPROVAL REQUIRED** | @pirathee587 (contracts), @Nilakshana23 (S3 lifecycle) |
| **G10** | BUG / BLOCKED | S2 | [#301](https://github.com/pirathee587/Zentro/issues/301) | `ParcelBookingService.price()` forwards caller `destinationType` directly; engine uses it for LKR 402 cross-district surcharge; no server-side city-to-zone classification exists. | **CHARACTERIZED — APPROVAL REQUIRED** | @pirathee587 (product) |
| **G11** | PARTIAL / BLOCKED | S2 | [#306](https://github.com/pirathee587/Zentro/issues/306) | `PricingCalculationEngine` calculates volumetric weight (`L×W×H/5000`) on estimate quotes; `CreateParcelBookingRequest` has no dimension fields; booking calculates fee solely on actual weight. | **CHARACTERIZED — APPROVAL REQUIRED** | @pirathee587 (product) |
| **G12** | MISSING / BLOCKED | S2 | [#305](https://github.com/pirathee587/Zentro/issues/305) | Booking permits identical sender and recipient address without warning or rejection; no same-address policy implemented. | **CHARACTERIZED — APPROVAL REQUIRED** | @pirathee587 (policy) |
| **G13** | RESOLVED / PRESERVED | S2 | [#194](https://github.com/pirathee587/Zentro/issues/194) | `PricingRuleUpdateRequest.currency` `@Pattern(regexp = "^[A-Za-z]{3}$")` resolved upstream (#120); `PricingService` & `ZoneService` default fallbacks on DB error preserved and tested. | **RESOLVED UPSTREAM & TESTED** | N/A (verified passing) |
| **G43** | PARTIAL / BLOCKED | S2 | [#257](https://github.com/pirathee587/Zentro/issues/257) | `MerchantOrderController.list` clamps `limit` query param to `[1, 100]`; returns flat `List<MerchantOrderResponse>` without pagination metadata or cursor. | **CHARACTERIZED — APPROVAL REQUIRED** | @pirathee587 (contracts) |
| **X01** | MISSING / BLOCKED | S2 | [#191](https://github.com/pirathee587/Zentro/issues/191) | POST `/api/v1/parcels` returns `201 Created` with `Location: /api/v1/parcels/{id}`, but S2 exposes no GET endpoint for that path (returns 404). Gateway denies unlisted routes. | **CHARACTERIZED — APPROVAL REQUIRED** | @pirathee587 (contracts/gateway) |

---

## 3. Characterization Findings Detail

### G01 — Authoritative dispatch candidate and guarded claim API
- **Problem:** S3 (`dispatch-courier-service`) requires enumerable candidate parcels in `CREATED` status to form dispatch queues and assign couriers. Because direct cross-service SQL is strictly prohibited by WARP.md architecture rules, S3 must consume this data from S2.
- **Current state:** `contracts/proto/parcel_pricing.proto` contains only `rpc GetParcel(GetParcelRequest) returns (ParcelResponse)`. S2 does not implement a gRPC server, and no enumeration or guarded claim RPC/REST endpoint exists.
- **Required decisions:**
  1. gRPC vs REST contract specification for candidate enumeration (`GetEligibleParcelsRequest`).
  2. Guarded atomic claim protocol (`ClaimParcelRequest` with conditional version/status check to guarantee single-winner assignment races).
  3. Lease timeout, release, and crash recovery mechanics.
- **Action:** Documented as blocked on cross-service contract agreement (#346).

### G10 — Server-owned destination classification
- **Problem:** Under default pricing rules, a parcel delivery from Colombo to Jaffna costs:
  - **LKR 550.00** if caller passes `destinationType = SAME_CITY`.
  - **LKR 952.00** if caller passes `destinationType = OTHER_DISTRICT`.
  The difference is **LKR 402.00** (`distanceSurchargeOtherDistrict`).
- **Evidence:** `PricingCalculationEngine.isCrossZoneOrOtherDistrict` evaluates:
  ```java
  if (request.destinationType() == DestinationType.OTHER_DISTRICT) {
    return true;
  }
  ```
  In customer parcel booking, `originZone` and `destinationZone` are always null because `CreateParcelBookingRequest` does not accept zones. `ParcelBookingService` forwards the client-supplied `destinationType` unmodified.
- **Executable verification:** `Zd353EngineAndServiceCharacterizationTest.g10_callerSuppliedDestinationTypeChangesFeeBy402Lkr` and `g10_bookingServiceForwardsCallerDestinationTypeDirectly` prove that:
  1. Same route fee varies by exactly LKR 402.00 based entirely on caller enum input.
  2. `ParcelBookingService` forwards caller `destinationType` directly to `PricingService` without deriving classification from sender/recipient city.
- **Required decision:** Server-owned city/district mapping database and transition contract for client callers (#301).

### G11 — Dimension quote versus booking consistency
- **Problem:**
  - `POST /api/v1/parcels/estimate` (`FeeEstimateRequest`) accepts dimensions `lengthCm`, `widthCm`, `heightCm`.
  - For a 50 × 40 × 30 cm parcel with actual weight 1.0 kg:
    $$\text{Volumetric Weight} = \frac{50 \times 40 \times 30}{5000} = 12.0\text{ kg}$$
    Chargeable weight is $\max(1.0, 12.0) = 12.0\text{ kg}$. Under standard default pricing (1.0 kg included, LKR 120.00/extra kg), the quote calculates:
    $$550.00 + (11.0 \times 120.00) = \text{LKR } 1870.00$$
  - However, `POST /api/v1/parcels` (`CreateParcelBookingRequest`) does not accept dimension fields.
  - The booking is priced using actual weight (1.0 kg), resulting in a fee of **LKR 550.00** — a **LKR 1320.00 discrepancy** between quote and booking.
- **Executable verification:** `Zd353EngineAndServiceCharacterizationTest.g11_dimensionQuoteVersusBookingDiscrepancy` asserts this LKR 1320.00 discrepancy.
- **Required decision:** Product and contract design on whether booking request schema should accept dimensions or quotation should restrict dimensions (#306).

### G12 — Same-address reject/warn policy
- **Problem:** `CreateParcelBookingRequest` permits identical sender and recipient address strings (e.g. `12 Galle Road, Colombo 03`).
- **Current behavior:** Booking succeeds with status `CREATED`, generates a tracking reference, records an outbox event, and charges a standard fee. No warning or rejection is issued.
- **Executable verification:** `Zd353EngineAndServiceCharacterizationTest.g12_bookingPermitsIdenticalSenderAndRecipientAddress` proves that identical addresses are accepted and processed normally.
- **Required decision:** Product policy on whether identical address bookings should be rejected (422), warned, or allowed (#305).

### G13 — Zone and pricing administration / default fallback policy
- **Audit result:**
  1. **Currency validation:** `PricingRuleUpdateRequest.currency` in `origin/develop` already includes `@Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter ISO code.")` (PR #120).
  2. **Database fallback:** `PricingService.getActiveRule()` falls back to `PricingRule.defaultStandard()` and `ZoneService.getActiveZones()` falls back to `defaultZones()` on `DataAccessException`. This protects service availability when migrations are pending or during database restart.
- **Executable verification:** `Zd353EngineAndServiceCharacterizationTest.g13_servicesFallbackToApprovedDefaultsOnDatabaseFailure` verifies that simulated DB failures return approved default configurations.

### G43 — Bounded list/history traversal and capacity decision
- **Audit result:**
  - `MerchantOrderController.list` exposes `GET /api/v1/merchant/orders?limit={limit}`.
  - The controller clamps the limit: `int clampedLimit = Math.min(Math.max(limit, 1), 100);` (default: 50).
  - The response is a flat JSON array `List<MerchantOrderResponse>` with no cursor or pagination envelope.
- **Executable verification:** `Zd353ControllerCharacterizationTest.g43_merchantOrderListClampsLimit` exercises boundary inputs (`-10`, `0`, `1`, `50`, `100`, `101`, `500`) and verifies that `orderService.listByMerchant` always receives clamped values in `[1, 100]`.
- **Required decision:** Pagination cursor design (`createdAt, id` tie-break) as documented in `docs/testing/performance-pagination-follow-ups.md` (#257).

### X01 — Authoritative parcel retrieval / booking Location
- **Problem:**
  - When a booking is created via `POST /api/v1/parcels`, the controller returns `201 Created` with header `Location: /api/v1/parcels/{id}`.
  - However, neither `ParcelBookingController` nor any other S2 controller implements a `GET /api/v1/parcels/{id}` endpoint.
  - When a client attempts to fetch the created resource via `GET /api/v1/parcels/{id}`, Spring MVC returns `404 Not Found`.
- **Executable verification:** `Zd353ControllerCharacterizationTest.x01_bookingReturnsLocationHeaderPointingToUnmappedGetEndpoint` verifies that the `Location` header is returned on 201, but the subsequent GET request to that exact URI returns 404.
- **Required decision:** Contract agreement on authoritative owner-scoped parcel retrieval API and gateway route registration (#191).

---

## 4. Verification Evidence

### Local Environment
- OS: Darwin / macOS (arm64)
- Java: OpenJDK 21.0.10
- Maven: 3.9.x
- Spring Boot: 3.5.16

### Test Execution Commands & Results

1. **Characterization Tests Execution:**
   ```bash
   cd backend && mvn test -pl parcel-pricing-service -am \
     -Dtest=Zd353EngineAndServiceCharacterizationTest,Zd353ControllerCharacterizationTest \
     -Dsurefire.failIfNoSpecifiedTests=false
   ```
   **Output:**
   - `Zd353ControllerCharacterizationTest`: 9 tests run, 0 failures, 0 errors, 0 skipped (3.307 s)
   - `Zd353EngineAndServiceCharacterizationTest`: 5 tests run, 0 failures, 0 errors, 0 skipped (0.337 s)
   - **Total:** 14 tests run, 0 failures, 0 errors, 0 skipped
   - **Build:** `BUILD SUCCESS` (5.656 s)

2. **Full Module Test Execution:**
   ```bash
   cd backend && mvn test -pl parcel-pricing-service -am
   ```
   **Output:**
   - `zentro-security-starter`: 39 tests run, 0 failures, 0 errors, 0 skipped
   - `parcel-pricing-service`: 702 tests run, 0 failures, 0 errors, 0 skipped
   - **Build:** `BUILD SUCCESS`

---

## 5. Reviewer Guidance for @Kiruthiyan

- **Scope:** S2 (`parcel-pricing-service`) characterization for ZD-353 (#353).
- **Files Modified/Added:**
  - `backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/parcel/service/Zd353EngineAndServiceCharacterizationTest.java` (added)
  - `backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/parcel/controller/Zd353ControllerCharacterizationTest.java` (added)
  - `docs/api/zd-353-dispatch-source-characterization.md` (added)
- **Checklist:**
  - [x] No cross-service SQL or direct database coupling introduced.
  - [x] No ADMIN primary role introduced (enforced 4-role model only).
  - [x] Existing production contracts and behavior strictly preserved.
  - [x] G10, G11, G12, G13, G43, X01 verified with reproducible assertions.
  - [x] Blocked items accurately identified with approval owners.
  - [x] Build and test suite pass cleanly without regressions.

