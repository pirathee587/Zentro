# Backend Critical / High Defect and Regression Evidence Ledger (G38)

**Audit Baseline Date:** 2026-11-13  
**Audited Base Commit:** `72408ff31d8278fe47266fcbfdacc5d7adc6f31a` (`develop`)  
**Lead Engineer:** @Kiruthiyan  
**Reviewer:** @Deadsecnote1  
**Scope:** Main Issue #355 (06/10 — Merchant APIs and Webhook Integration), tracking verification of historical issues #180, #198, #199, #225 and upstream dependencies.

---

## 1. Overview & Verification Policy

In accordance with SwiftDrop engineering safety rules, task completion and defect closure are never claimed from aggregate green builds alone. Every confirmed Critical or High severity finding requires:
1. An executable regression test reproducing the defect or pinning the required contract.
2. Verified passing test execution against the candidate commit SHA.
3. Traceability to merged PR, owning author, and independent reviewer.
4. Explicit recording of policy blockers where architectural or security decisions prevent automated closure.

---

## 2. Critical & High Defect Inventory

| Defect / Gap ID | Severity | Component & Focus | Owning Issue & PR | Author / Reviewer | Status | Merged SHA / Candidate SHA | Verification Evidence & Tests |
|---|---|---|---|---|---|---|---|
| **G01 (ZD-01)** | Critical | S1 User Account Status & Partner Lifecycle | #350 (PR #365) | @kirusigan03 / @pavi3alnx | **VERIFIED** | `4b3017a` | `DisabledAccountEnforcementTest`, `KeycloakRealmConfigurationTest`, `ProtobufWireCompatibilityTest` |
| **G02 (ZD-02)** | Critical | Gateway Routes, SSE & Contract Inventory | #351 (PR #366) | @pavi3alnx / @kirusigan03 | **VERIFIED** | `c27c3da` | `RateLimitFilterTest`, `GatewaySseContractTests`, `build_openapi.py --check` (58 ops) |
| **G04 (ZD-04)** | Critical | S2 Parcel Booking, Pricing & Retrieval | #353 (PR #368) | @Jathuja / @Kiruthiyan | **VERIFIED** | `87b4800` | `ParcelRetrievalControllerTest`, `MerchantOrderRepositorySortTest`, `Zd353EngineAndServiceCharacterizationTest` |
| **G05 (ZD-05)** | Critical | S3 Dispatch Courier Lifecycle & POD | #354 (PR #369) | @Nilakshana23 / @Kiruthiyan | **VERIFIED** | `72408ff` | `AssignmentPostgresIntegrationTest`, `ProofPostgresIntegrationTest`, `OutboxPublisherTest` |
| **G18 (ZD-121)** | High | S5 Webhook Retry, Lease Safety & Replay | #199 / #355 (PR #340) | @Kiruthiyan / @Deadsecnote1 | **VERIFIED** | Current (`develop`) | `WebhookRetryTestingTest` (639 lines, 8 attempts backoff), `IntegrationAttemptControllerTest` (Replay: 204, 404, 409), `WebhookReplayProtectionTest` |
| **G19 (ZD-120)** | High | Merchant API-Key Authentication vs Bearer JWT | #198 / #355 | @Kiruthiyan / @Deadsecnote1 | **PARTIAL / BLOCKED** | Current (`develop`) | S1 Key lifecycle verified in `MerchantApiKeyServiceTest` & `MerchantApiKeyControllerTest`. Direct raw-key HTTP header (`X-API-Key`) **BLOCKED** pending security approval (#350/#351). |
| **G35 (ZD-178)** | High | Merchant Order to Webhook E2E Pipeline | #225 / #355 | @Kiruthiyan / @Deadsecnote1 | **VERIFIED** | Current (`develop`) | `MerchantOrderIntegrationTest` (idempotency, owner scoping, outbox), `MerchantOrderOutboxAtomicityIntegrationTest`, `MerchantApprovalGapIT` |
| **G10 (ZD-10)** | Critical | Base Pricing Rule Integrity & Negative Weights | #301 | @Jathuja | **BLOCKED** | Pending #301 | Price integrity policy under active review. Code preserves existing validation. |
| **G11 (ZD-11)** | High | Fuel Surcharge Multiplier & Geo Bounds | #305 | @Jathuja | **BLOCKED** | Pending #305 | Pending product stakeholder decision. |
| **G12 (ZD-12)** | High | Peak Season Surcharge Enforcement | #306 | @Jathuja | **BLOCKED** | Pending #306 | Pending product stakeholder decision. |
| **G27 (ZD-181)** | Medium | Canonical OpenAPI & Architecture Contract Freeze | #181 / #355 | @Kiruthiyan / @Deadsecnote1 | **VERIFIED** | Current (`develop`) | `docs/api/build_openapi.py --check` passes for all 5 microservices (58 operations synchronized). |

---

## 3. Deep-Dive Verification Details

### 3.1 Webhook Retry, Lease Safety, and Dispatcher Replay (G18)
- **Exponential Backoff**: Verified in `WebhookRetryTestingTest`. Follows $10\text{ s} \times 2^{(\text{attempt} - 1)}$ formula: 10s, 20s, 40s, 80s, 160s, 320s, 640s, capped strictly at 30 minutes (1800s).
- **Retry Boundary**: Attempt 7 schedules retry; Attempt 8 fails permanently with terminal exhaustion mark.
- **Status Classification**:
  - `2xx` -> `SUCCEEDED`
  - `408`, `429`, `5xx`, connection timeout, read timeout -> `RETRY`
  - Non-retryable `4xx` (e.g. 400, 404, 422), `3xx` redirects -> `FAILED` immediately without retry
- **Concurrent Schedulers & Lease Safety**: Database query in `WebhookDeliveryRepository.lockForAttempt()` uses `FOR UPDATE OF d SKIP LOCKED`, preventing multiple instances from sending the same delivery concurrently.
- **Dispatcher Replay (`POST /api/v1/admin/webhook-deliveries/{deliveryId}/replay`)**:
  - Requires `ROLE_DISPATCHER`.
  - Non-existent delivery returns `404 Not Found`.
  - Invalid UUID returns `400 Bad Request`.
  - Non-FAILED delivery returns `409 Conflict`.
  - Disabled subscription returns `409 Conflict`.
  - Already replayed delivery returns `409 Conflict`.
  - Successfully replayed delivery returns `204 No Content` and commits an audit row to `notification.webhook_delivery_replay (delivery_id, dispatcher_subject)`.

### 3.2 Merchant API-Key Authentication Policy (G19)
- **Current Approved Contract**: Bearer JWT (`Authorization: Bearer <token>`) issued by Keycloak with `ROLE_MERCHANT`.
- **S1 Key Lifecycle**:
  - SHA-256 storage (`key_hash = CHAR(64)`), prefix display (`zn_live_xxxxxxxx`).
  - Secret shown once upon creation/rotation.
  - Requires `APPROVED` merchant application and `ACTIVE` user account.
  - Revocation is immediate.
- **Direct API-Key Authentication Blocker**:
  - #198 and historical #225 envisioned raw `X-API-Key` headers on `/api/v1/merchant/orders`.
  - **Policy Decision**: Direct raw-key HTTP transport is blocked pending security architecture approval (#350/#351). Replacing Bearer JWT with API-key headers without gateway auth adapter and Keycloak integration is rejected. The API continues to enforce Bearer JWT with `ROLE_MERCHANT`.

### 3.3 Merchant Order and Webhook Acceptance (G35)
- **Order Creation & Idempotency**:
  - First submission: `201 Created` with `Location: /api/v1/merchant/orders/{id}` and tracking reference `ZT-...`.
  - Atomically writes parcel row and `parcel.created` outbox event (`source = MERCHANT_ORDER`, `merchantId`).
  - Idempotent resubmission with matching `externalOrderRef`: returns `200 OK` with existing order and emits NO extra outbox event.
  - Conflicting body with matching `externalOrderRef`: returns `409 Conflict`.
- **Subject Scoping & Cross-Merchant Isolation**:
  - `GET /api/v1/merchant/orders/{id}` returns `404 Not Found` if accessed by a different merchant.
  - `GET /api/v1/merchant/orders` lists only orders belonging to authenticated merchant (`ORDER BY createdAt DESC, id DESC`).
- **Webhook Delivery**:
  - Consumed from Kafka by S5 `merchant-webhooks` consumer.
  - Verified signed delivery with HMAC-SHA256 (`X-SwiftDrop-Signature`), stable event ID (`X-SwiftDrop-Event-Id`), and timestamp (`X-SwiftDrop-Timestamp`).

---

## 4. Summary of Blocked Items

| Issue | Description | Blocked By | Action Required |
|---|---|---|---|
| #198 (partial) | Direct raw `X-API-Key` HTTP authentication | Security architecture decision | Security team review for gateway adapter & service authentication design |
| #301 | Base pricing calculation rules | Product management | Formal resolution of price calculation edge cases |
| #305 | Fuel surcharge calculation rules | Product management | Formal definition of geographic surcharge tiers |
| #306 | Peak season surcharge policy | Product management | Formal definition of seasonal multipliers |
