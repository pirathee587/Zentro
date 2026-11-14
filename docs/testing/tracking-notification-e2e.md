# Tracking, Notification & Receipts End-to-End Verification (ZD #357)

This document specifies the verification model, lifecycle workflows, contract boundaries, and known dependency blockers for tracking projections, live SSE streams, and billing receipts.

---

## 1. Tracking Projections Architecture (S4: tracking-reporting-service)

### 1.1 Booking-to-Tracking Initialization (G04 Resolution)
- **Automatic Projection Initialization:** When a `parcel.created` event arrives with status `CREATED`, `TrackingService.recordEvent` initializes a minimal `TrackingParcel` projection row if absent, ensuring public tracking is immediately resolvable (`GET /api/v1/public/tracking/{trackingRef}`) without manual DB seeding.
- **Explicit Programmatic Initialization:** `TrackingService.initializeTrackingParcel(...)` is provided for atomic booking-to-tracking initialization from upstream booking flows.
- **Backfill & Reconciliation:** `TrackingService.backfillTrackingParcel(trackingRef)` inspects the chronological `TrackingEvent` stream and reconciles missing or desynchronized parcel projection rows.

### 1.2 Assignment & Reassignment Projections
- **Kafka Listener Topics:** `delivery.assigned`, `delivery.reassigned`, `delivery.cancelled` published by S3 (`dispatch-courier-service`).
- **Assignment Handling:** Updates `TrackingParcel.courierSubject`.
- **Reassignment / Cancellation Privacy Revocation:** When a delivery is reassigned or cancelled, `TrackingStreamService.revokeSubscriber` terminates the active SSE stream for the former courier. Subsequent retrieval attempts by the former courier return `403 Forbidden` / `AccessDeniedException`.

### 1.3 Merchant History Query Fallback (X02 Resolution)
- `TrackingService.getCustomerDeliveries` checks `paged == null || paged.isEmpty()` before falling back to `findByCustomerOrMerchantSubject`. Because Spring Data repositories return `Page.empty()` rather than `null`, the previous null-only check caused merchant-owned orders to disappear from history.

### 1.4 PII and Privacy Redaction
- `PublicTrackingResponse` excludes sender phone, sender address, recipient phone, recipient address, customer subject, merchant subject, and fee.
- Only safe tracking reference, portal status, raw status, origin city, destination city, estimated delivery, and public milestone timeline are exposed.

---

## 2. Receipt Subsystem Architecture (S2: parcel-pricing-service)

### 2.1 Contracts & Endpoints
- `GET /api/v1/parcels/{id}/receipt`
  - Content-Type: `application/json`
  - Returns `ReceiptResponse` metadata: id, receiptNumber, parcelId, trackingRef, amount, formattedAmount, currency, paymentStatus, paymentMethod, downloadUrl, formattedDate.
- `GET /api/v1/parcels/{id}/receipt/pdf`
  - Content-Type: `application/pdf`
  - Content-Disposition: `inline; filename="receipt-{trackingRef}.pdf"`
  - Valid binary PDF stream (`%PDF-1.4`) with printable receipt layout.

### 2.2 Guarantees & Non-Repricing
- The receipt fee and currency are read strictly from the persisted `parcel.parcel` row (`fee`, `currency`).
- `PricingService` is never invoked during receipt generation, preventing currency re-estimation, rate fluctuation, or re-calculation drift.

### 2.3 Access Control & Zero-Trust Authorization
- `@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'DISPATCHER', 'SERVICE')")`
- **Customer Scope:** `customerSubject == jwt.subject`. Access to a foreign parcel or non-existent parcel returns `404 Not Found` to prevent IDOR and resource enumeration.
- **Merchant Scope:** `merchantId == jwt.subject`. Scoped to `parcel.merchant_id`.
- **Privileged Scope:** `DISPATCHER` and `SERVICE` roles can access any valid parcel receipt.

---

## 3. Dependency Blockers Ledger

| Dependency Issue | Owning Service / Assignee | Impact on ZD #357 | Status & Mitigation |
|------------------|---------------------------|-------------------|---------------------|
| **#351 Gateway Route** | `api-gateway` / Main 02 | `GET /api/v1/parcels/{id}/receipt` and `/pdf` need external routing configuration in Spring Cloud Gateway for customer and merchant web access. | Internal service controller complete; ready for gateway route mapping. |
| **#353 Dispatch Source** | `dispatch-courier-service` / S3 Assignee | S3 outbox publisher must be enabled (`OUTBOX_ENABLED=true`) in production to emit `delivery.assigned`, `delivery.reassigned`, `delivery.cancelled` to Kafka. | S4 consumer and handler implemented and tested against S3 outbox schema. |
| **#354 Courier Lifecycle** | `dispatch-courier-service` / S3 Maintainer | Courier status progression (`PICKED_UP`, `OUT_FOR_DELIVERY`) transitions. | S4 timeline handles out-of-order and tied events chronologically. |

---

## 4. Test Verification Matrix

| Component | Test Class | Coverage Scope | Status |
|-----------|------------|----------------|--------|
| S4 Tracking Record Creation | `TrackingRecordCreationCharacterizationTest` | Initial projection creation on CREATED event; 404 for missing | PASS |
| S4 Merchant History Scoping | `MerchantHistorySubjectScopingTest` | Resolution of empty-page fallback for merchant deliveries | PASS |
| S4 Privacy & Boundaries | `TrackingPrivacyAndBoundaryTest` | IDOR boundaries, field-level PII redaction from public response | PASS |
| S4 History Filters | `TrackingHistoryFilterTest` | Status filter combinations, search queries, pagination clamping | PASS |
| S4 Duplicate Events | `TrackingDuplicateEventProcessingTest` | Idempotent deduplication via processed_event, replay rejection | PASS |
| S4 Reordered Events | `TrackingReorderedEventTest` | Chronological ordering of timeline milestones across arrivals | PASS |
| S4 Tied Timestamps | `TrackingTiedTimestampsAndTerminalTest` | Tied timestamps preservation, terminal DELIVERED/FAILED state | PASS |
| S4 Assignment Projections | `TrackingKafkaConsumerTest` | `delivery.assigned`, `reassigned`, `cancelled` handling | PASS |
| S4 Stream Security | `TrackingStreamServiceTest` | SSE token expiry disconnect, wrong-owner rejection, reconnect | PASS |
| S4 End-to-End Flow | `BookingToTrackingProjectionTest` | Complete booking -> projection -> history -> delivery lifecycle | PASS |
| S2 Receipt Generation | `ReceiptGenerationTest` | Persisted fee preservation (no repricing), currency, PDF bytes | PASS |
| S2 Receipt Authorization | `ReceiptAuthorizationAndBoundaryTest` | 401 unauthenticated, 404 cross-owner IDOR, DISPATCHER access | PASS |
