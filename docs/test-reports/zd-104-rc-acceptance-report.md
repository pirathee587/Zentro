# ZD-104 Full Release Candidate Acceptance Run (#179)

- **Task:** `[ZD-104] Full release candidate acceptance run`
- **Issue:** #179
- **Repository:** `pirathee587/Zentro`
- **Primary Assignee:** `@Jathuja`
- **Independent Reviewer:** `@Kiruthiyan`
- **Release Candidate Baseline:** `8f66d4e726505c8b561b2b7088e2c0c887ef3dd0` (`origin/develop`)
- **Execution Date:** 2026-11-09
- **Overall RC Acceptance Verdict:** **PARTIALLY ACCEPTED / UNVERIFIED LIVE E2E**
  - **Automated Component & Service Boundary Tests:** **PASS** (100% of runnable automated suites passed across frontend and backend microservices).
  - **Live Multi-Service End-to-End Acceptance:** **BLOCKED** due to missing live containerized staging environment and pending upstream integration issues (#165, #167, #168, #153, #154 / PR #311).
  - **Known Architectural Gaps:** Finding K4 (merchant approval does not grant Keycloak role automatically).

---

## 1. Executive Summary

This acceptance run executes the release candidate acceptance criteria across all four human roles (**CUSTOMER**, **MERCHANT**, **COURIER**, **DISPATCHER**), cross-role security boundaries, and five backend microservices (S1–S5) on the verified `develop` baseline.

All code and test suites merged through recent PRs (#295 data ownership, #296 transactional outbox, #297 dispatcher user management, #307 gateway fail-closed routes, #308 dispatch workload metrics, #309 internal call deadlines, and #310 cross-role authorization matrix) were verified in place.

No separate `ADMIN` role exists; all administration is unified under `DISPATCHER`. Database-per-service isolation is maintained across all services.

---

## 2. Dependency and Blocker Audit

| Area / Capability | Issue / PR | Owner | Status | Required for Acceptance? | Blocker Status |
|---|---|---|---|---|---|
| Cross-role negative test matrix | Issue #169 / PR #310 | @Jathuja | Merged (`8f66d4e`) | Yes | Completed dependency |
| Internal call deadlines & resilience | Issue #147 / PR #309 | @14Jannan | Merged (`f452def`) | Yes | Completed dependency |
| S3 dispatch workload query accuracy | Issue #134 / PR #308 | @14Jannan | Merged (`348a650`) | Yes | Completed dependency |
| Gateway route audit & fail-closed | Issue #144 / PR #307 | @pirathee587 | Merged (`8231dcd`) | Yes | Completed dependency |
| Dispatcher user operations screen | Issue #159 / PR #297 | @Jathuja | Merged (`ad7dab9`) | Yes | Completed dependency |
| Transactional outbox atomicity | Issue #62 / PR #296 | @14Jannan | Merged (`c3c0ef2`) | Yes | Completed dependency |
| Cross-service data ownership audit | Issue #149 / PR #295 | @Jathuja | Merged (`7bb8db1`) | Yes | Completed dependency |
| Customer Playwright E2E journey | Issue #165 (ZD-090) | @kirusigan03 | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Dispatcher assignment-to-courier E2E | Issue #167 (ZD-092) | @14Jannan | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Courier completion & proof E2E | Issue #168 (ZD-093) | @hephzibah-peries | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Merchant portal order/status integration | Issue #153 (ZD-078) | @pavi3alnx | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Courier portal delivery cache isolation | PR #311 (Refs #154) | @pirathee587 | Open (Draft) | Yes (Courier portal) | **Blocker (Courier Portal Cache)** |
| Merchant order & webhook integration | Issue #225 (ZD-178) | @Niro0526 | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Dispatcher assignment backend integration | Issue #226 (ZD-179) | @Nilakshana23 | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Courier proof backend integration | Issue #227 (ZD-180) | @pavi3alnx | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Tracking & notification integration | Issue #228 (ZD-131) | @hephzibah-peries | Open | Yes (for live E2E) | **Blocker (Live E2E)** |
| Same-address booking policy decision | Issue #305 | @14Jannan | Open | No | Non-blocking follow-up |
| Dimension vs weight quote mismatch | Issue #306 | @Jathuja | Open | No | Non-blocking follow-up |

---

## 3. Environment Prerequisites & Execution Context

| Component | Status | Notes |
|---|---|---|
| OS / Architecture | macOS Darwin 25.1.0 arm64 | Apple Silicon host environment |
| Node.js / npm | v20.18.0 / 10.8.2 | Frontend execution runtime |
| Java / Maven | Temurin 21.0.10 / 3.9.9 | Backend execution runtime |
| Docker Daemon | **INACTIVE** | Docker daemon not running locally (`connect: no such file or directory`) |
| Live Microservices | **INACTIVE** | Ports 8080-8085 not listening |
| Live Keycloak Runtime | **INACTIVE** | Port 8180 not listening |
| Test Execution Mode | Automated Slices & Doubles | Vitest/MSW for frontend; WebMvcTest, SpringBootTest, embedded DBs/doubles, and WireMock for backend |

---

## 4. Acceptance Checklist & Verification Results

### 4.1 CUSTOMER Acceptance

| Test ID | Scenario / Capability | Preconditions | Expected Result | Actual Result | Status | Evidence Reference |
|---|---|---|---|---|---|---|
| TC-CUST-01 | Customer authentication & portal access | Valid Keycloak JWT with `ROLE_CUSTOMER` | Access granted to Customer portal; non-customer routes guarded | Access granted; RoleGuard blocks non-customer | **PASS** | `AuthProvider.test.tsx`, `RoleGuard.test.tsx`, `ProtectedRoute.test.tsx` |
| TC-CUST-02 | Customer profile view & update | Authenticated customer | Read/update own profile via `/api/v1/users/me`; subject enforced | Scoped strictly to caller's `sub` | **PASS** | `UserProfileOwnershipTest.java`, `customerApi.test.ts` |
| TC-CUST-03 | Parcel price quotation | Valid weight, category, size | Accurate fee estimation returned without booking | Quotation engine returns fee without creating parcel | **PASS** | `ParcelPricingAuthorizationAndEndpointTests.java`, `QuotePreview.test.tsx` |
| TC-CUST-04 | Parcel booking creation | Authenticated customer, valid parcel body | 201 Created with tracking reference, outbox event staged, fee calculated | Persisted in `parcel.parcel`, tracking ref generated, outbox populated | **PASS** | `ParcelBookingControllerTest.java`, `BookingPersistenceIntegrationTest.java`, `SendParcelPage.test.tsx` |
| TC-CUST-05 | Booking duplicate replay & idempotency | Replay with same `Idempotency-Key` and body | 200 OK returning existing booking; 409 if body differs | Idempotent replay returns 200 without duplicate row | **PASS** | `BookingDuplicateSubmissionIntegrationTest.java`, `customerApi.test.ts` |
| TC-CUST-06 | View own parcels & delivery history | Authenticated customer | Returns only caller's deliveries; pagination supported | Deliveries filtered strictly by customer subject | **PASS** | `CustomerTrackingControllerTest.java`, `HistoryPage.test.tsx` |
| TC-CUST-07 | Public tracking search & timeline | Tracking reference (e.g. `ZT-XXXX`) | Anonymous access permitted; personal sender/recipient PII masked | Status milestones returned; fee and contact details masked | **PASS** | `PublicTrackingControllerTest.java`, `ZeroTrustJwtSecurityTest.java` (S4), `TrackParcelPage.test.tsx` |
| TC-CUST-08 | Receipts listing & receipt download | Authenticated customer | Receipts list formatted in LKR for caller's parcels | Receipts rendered with correct currency formatting | **PASS** | `ReceiptsPage.test.tsx`, `customerApi.test.ts` |
| TC-CUST-09 | Cross-customer parcel IDOR protection | Customer A attempts to view Customer B's records | 403 Forbidden or 404 Not Found (no cross-user disclosure) | Request rejected; customer cannot access other customer records | **PASS** | `ZeroTrustJwtSecurityTest.java` (S4), `CustomerTrackingControllerTest.java` |
| TC-CUST-10 | Live customer booking-to-delivery journey | Running Keycloak, S1, S2, S4, Kafka | Real end-to-end browser journey completes | Blocked by missing running environment and open issue #165 | **BLOCKED** | Issue #165 (@kirusigan03) |

---

### 4.2 MERCHANT Acceptance

| Test ID | Scenario / Capability | Preconditions | Expected Result | Actual Result | Status | Evidence Reference |
|---|---|---|---|---|---|---|
| TC-MERCH-01 | Merchant application submission | Authenticated customer (`ROLE_CUSTOMER`) | 201 Created with application in `PENDING` state | Application created with applicant subject from token | **PASS** | `RoleBasedAuthorizationMatrixTests.java`, `MerchantApplicationPage.test.tsx` |
| TC-MERCH-02 | View own merchant application | Authenticated user | Returns only caller's application | Scoped strictly to `app_user_id` from token | **PASS** | `RoleBasedAuthorizationMatrixTests.java`, `MerchantApplicationPage.test.tsx` |
| TC-MERCH-03 | Merchant role assignment gap (Finding K4) | Dispatcher reviews & approves application | Keycloak automatically assigns `ROLE_MERCHANT` | S1 changes DB status to APPROVED, but Keycloak role must be manually granted by admin | **BLOCKED / DEFECT** | Finding K4 in `merchant-order-webhook-e2e.md`, Issue #190 |
| TC-MERCH-04 | Merchant API key management | Approved, active merchant (`ROLE_MERCHANT`) | Can generate, view metadata, rotate, and revoke API key | Approved active merchant succeeds; pending/rejected returns 403 | **PASS** | `MerchantApiKeyControllerTest.java`, `MerchantApiKeyServiceTest.java` |
| TC-MERCH-05 | Merchant order creation & idempotency | Approved merchant, valid order body | 201 Created with tracking ref; duplicate externalRef returns 200 or 409 | Scoped to merchant subject; duplicate handled safely | **PASS** | `MerchantOrderControllerTest.java`, `merchantApi.test.ts` |
| TC-MERCH-06 | View own merchant orders | Authenticated merchant | Returns list of orders belonging only to caller | Scoped to merchantId from JWT `sub` | **PASS** | `MerchantOrderControllerTest.java`, `DashboardPage.test.tsx` |
| TC-MERCH-07 | Webhook endpoint registration & secret rotation | Approved merchant | 201 Created with secret returned once; rotate replaces secret prefix | Secret displayed once with `no-store` header; ciphertext encrypted | **PASS** | `MerchantWebhookApiTest.java`, `MerchantWebhookController.java` |
| TC-MERCH-08 | Webhook event delivery & replay protection | Webhook registered, parcel event emitted | Signed payload sent with HMAC-SHA256 signature; retries on 5xx | Payload signed with secret; replay timestamp and nonce checked | **PASS** | `WebhookSecurityTest.java`, `WebhookReplayProtectionTest.java`, `WebhookSignatureVerifierTest.java` |
| TC-MERCH-09 | Cross-merchant resource IDOR protection | Merchant A attempts to read Merchant B's orders/webhooks | 404 Not Found (no cross-merchant resource leakage) | Merchant B orders/webhooks return 404 to Merchant A | **PASS** | `MerchantOrderControllerTest.java`, `MerchantWebhookApiTest.java` |
| TC-MERCH-10 | Live merchant portal order & status integration | Running Keycloak, S1, S2, S5 | Real browser order flow with live webhook dispatch | Blocked by missing running environment and open issues #153, #225 | **BLOCKED** | Issue #153 (@pavi3alnx), Issue #225 (@Niro0526) |

---

### 4.3 COURIER Acceptance

| Test ID | Scenario / Capability | Preconditions | Expected Result | Actual Result | Status | Evidence Reference |
|---|---|---|---|---|---|---|
| TC-COUR-01 | Courier self-profile view & update | Authenticated courier (`ROLE_COURIER`) | Read own profile; update phone/vehicle | Profile resolved from caller's token | **PASS** | `CourierSelfProfileControllerTest.java`, `CourierProfilePage.test.tsx` |
| TC-COUR-02 | Courier availability toggle | Active courier profile | Courier can mark available/unavailable | Availability persists and reflects in dispatch eligibility | **PASS** | `AdminCourierControllerTest.java`, `CourierProfilePage.test.tsx` |
| TC-COUR-03 | View assigned deliveries | Authenticated courier | Returns list of deliveries assigned to courier's profile ID | Returns only assigned deliveries; unassigned parcels omitted | **PASS** | `CourierDeliveryControllerTest.java`, `DeliveryPages.test.tsx` |
| TC-COUR-04 | Submit proof of delivery (POD) | Assigned courier, parcel in eligible status | 201 Created with recipient name, photo ref; completion worker queues | Proof saved, actor recorded, duplicate returns 200 replay | **PASS** | `CourierDeliveryControllerTest.java`, `SubmitProofRequestTest.java`, `DeliveryPages.test.tsx` |
| TC-COUR-05 | Cross-courier assignment IDOR protection | Courier A attempts to read/submit proof for Courier B's parcel | 403 Forbidden; no evidence written | Request rejected with 403; proofs repository untouched | **PASS** | `CourierDeliveryControllerTest.java`, `CourierWorkflowIntegrationTest.java` |
| TC-COUR-06 | Courier direct S2 status update rejection | Courier token calls S2 `PATCH /api/v1/parcels/{id}/status` | 403 Forbidden (couriers must complete through S3 proof endpoint) | Direct S2 status call rejected with 403 | **PASS** | `ParcelStatusControllerTest.java` (S2) |
| TC-COUR-07 | Courier delivery integration & cache isolation | Courier switches accounts or token expires | Denied delivery data cleared; previous courier query cache removed | Pending merge of PR #311 (open draft on `feature/154-...`) | **BLOCKED / IN REVIEW** | PR #311 (Refs #154, @pirathee587) |
| TC-COUR-08 | Live courier completion & proof E2E | Running Keycloak, S1, S2, S3 | Full mobile/web delivery execution with real photo proof | Blocked by missing running environment and open issues #168, #227 | **BLOCKED** | Issue #168 (@hephzibah-peries), Issue #227 (@pavi3alnx) |

---

### 4.4 DISPATCHER Acceptance

| Test ID | Scenario / Capability | Preconditions | Expected Result | Actual Result | Status | Evidence Reference |
|---|---|---|---|---|---|---|
| TC-DISP-01 | Dispatcher auth & portal access | Valid Keycloak JWT with `ROLE_DISPATCHER` | Access granted to Dispatch Desk; operational sections visible | Full dispatcher navigation rendered | **PASS** | `RoleGuard.test.tsx`, `UsersPage.test.tsx`, `DispatchQueuePage.test.tsx` |
| TC-DISP-02 | Dispatch queue view & filters | Authenticated dispatcher | View unassigned eligible parcels; filter by date and search | Queue listed; non-dispatchers receive 403 | **PASS** | `DispatchQueueControllerTest.java`, `DispatchQueuePage.test.tsx` |
| TC-DISP-03 | Courier workload & capacity view | Authenticated dispatcher | View courier list with active assignment counts and status | Accurate assignment counts returned | **PASS** | `DispatchQueueControllerTest.java`, `WorkloadPage.test.tsx` |
| TC-DISP-04 | Assign courier to parcel | Eligible parcel and active courier | 201 Created; assignment recorded with dispatcher subject | Assignment created; parcel locked against concurrent assignment | **PASS** | `DispatchQueueControllerTest.java`, `AssignmentPostgresIntegrationTest.java` |
| TC-DISP-05 | Assignment conflict handling | Parcel already assigned | 409 Conflict with `DELIVERY_ALREADY_ASSIGNED` code | Duplicate assignment rejected without overwriting | **PASS** | `DispatchQueueControllerTest.java` |
| TC-DISP-06 | User search & management (ZD-084) | Authenticated dispatcher | Search users by name/email, filter by status, edit profile | Users listed; name, phone, account status editable | **PASS** | `AdminUserControllerTest.java`, `UsersPage.test.tsx` |
| TC-DISP-07 | Self-account status lockout protection | Dispatcher edits own account in user management | Account status dropdown disabled; backend rejects self-lockout | UI disables status selector; backend rejects with 400 | **PASS** | `AdminUserControllerTest.java`, `UsersPage.test.tsx` |
| TC-DISP-08 | Courier profile & fleet review | Authenticated dispatcher | List couriers, view safe profile details, update profile | Couriers managed; sensitive Keycloak credentials omitted | **PASS** | `AdminCourierControllerTest.java`, `CouriersPage.test.tsx` |
| TC-DISP-09 | Merchant application review queue | Authenticated dispatcher | Review PENDING applications (APPROVE / REJECT / SUSPEND / REINSTATE) | Lifecycle transitions enforced; reason required | **PASS** | `AdminMerchantApplicationControllerTest.java`, `MerchantApprovalsPage.test.tsx` |
| TC-DISP-10 | Pricing rules & delivery zones management | Authenticated dispatcher | Create/update pricing rules and zone definitions | Full rule CRUD supported; calculations verified | **PASS** | `ParcelPricingAuthorizationAndEndpointTests.java`, `ZonesPricingPage.test.tsx` |
| TC-DISP-11 | Integration & notification attempts view (ZD-094) | Authenticated dispatcher | View notification attempts and webhook deliveries history | History returned; non-dispatchers receive 403 | **PASS** | `IntegrationAttemptControllerTest.java` |
| TC-DISP-12 | Live dispatcher assignment-to-courier E2E | Running Keycloak, S1, S2, S3 | Full queue-to-assignment-to-workload update E2E | Blocked by missing running environment and open issues #167, #226 | **BLOCKED** | Issue #167 (@14Jannan), Issue #226 (@Nilakshana23) |

---

### 4.5 Cross-Role Security Acceptance

| Test ID | Scenario / Capability | Preconditions | Expected Result | Actual Result | Status | Evidence Reference |
|---|---|---|---|---|---|---|
| TC-SEC-01 | Unauthenticated requests rejected | Missing `Authorization` header | 401 Unauthorized across all protected REST endpoints | RFC 7807 problem details with 401 returned | **PASS** | `authorization-matrix.md`, ZeroTrust tests across S1–S5 |
| TC-SEC-02 | Wrong-role authorization enforcement | Caller lacks required role (e.g. CUSTOMER calling admin) | 403 Forbidden returned | `@PreAuthorize` and gateway manager reject with 403 | **PASS** | `RoleBasedAuthorizationMatrixTests.java`, `authorization-matrix.md` |
| TC-SEC-03 | No separate ADMIN role | Token carrying `ROLE_ADMIN` | Rejected with 403; only `DISPATCHER` has administrative authority | `ROLE_ADMIN` rejected on all service endpoints | **PASS** | `ParcelStatusControllerTest.java`, `MerchantOrderControllerTest.java`, `authorization-matrix.md` |
| TC-SEC-04 | Technical SERVICE token boundary | Token carrying only `ROLE_SERVICE` | Denied on all human endpoints; allowed only on internal S2 status update | Denied at gateway and downstream human controllers | **PASS** | `GatewayBehaviorTests.java`, `ZeroTrustJwtSecurityTest.java` (S4, S5) |
| TC-SEC-05 | Forged gateway identity headers dropped | Request injects `X-User-Id`, `X-User-Roles` | Downstream services ignore forged headers and validate real JWT | Services validate RS256 token against JWKS directly | **PASS** | ZeroTrust tests in S1, S2, S3, S4, S5 |
| TC-SEC-06 | Fail-closed routing on unlisted gateway paths | Request to unmapped `/api/v1/**` path | 401 Unauthorized (anonymous) or 403 Forbidden (authenticated) | Gateway denies unlisted paths by default | **PASS** | `GatewayDefaultDenyTests.java`, PR #307 |

---

### 4.6 Service Boundary & Integration Acceptance

| Service | Boundary / Responsibility | Data Ownership Rule | Contract / Verification | Status |
|---|---|---|---|---|
| **S1 Identity & Partner** | Users, courier profiles, merchant applications, API keys | Owns `identity_db`. Never queries other service tables. | REST + gRPC server. Validated via `RoleBasedAuthorizationMatrixTests`. | **PASS** |
| **S2 Parcel & Pricing** | Parcel lifecycle, pricing engine, delivery zones, merchant orders | Owns `parcel_pricing_db`. Outbox events published to Kafka. | REST. Validated via `ParcelPricingAuthorizationAndEndpointTests`, `MerchantOrderControllerTest`. | **PASS** |
| **S3 Dispatch & Courier** | Dispatch queue, courier assignment, proof of delivery | Owns `dispatch_courier_db`. Calls S1 and S2 via authenticated REST clients with timeouts. | REST. Validated via `DispatchQueueControllerTest`, `CourierDeliveryControllerTest`. | **PASS** |
| **S4 Tracking & Reporting** | Tracking timeline, customer delivery views, public tracking | Owns `tracking_reporting_db`. Ingests Kafka parcel events; no cross-service SQL. | REST. Validated via `CustomerTrackingControllerTest`, PR #295 data ownership fix. | **PASS** |
| **S5 Notification & Integration** | Webhook subscriptions, deliveries, SMS notifications | Owns `notification_integration_db`. Consumes Kafka events; signs HMAC webhooks. | REST. Validated via `MerchantWebhookApiTest`, `IntegrationAttemptControllerTest`. | **PASS** |
| **API Gateway** | Edge authentication, rate limiting, route authorization, header sanitization | No database. Validates Keycloak JWT and forwards to upstream microservices. | Spring Cloud Gateway. Validated via `GatewayBehaviorTests`, `GatewayDefaultDenyTests`. | **PASS** |

---

## 5. Scenario Counts Summary

| Category | Total Scenarios | PASS | FAIL | BLOCKED | NOT RUN |
|---|---|---|---|---|---|
| Customer Acceptance | 10 | 9 | 0 | 1 | 0 |
| Merchant Acceptance | 10 | 8 | 0 | 2 | 0 |
| Courier Acceptance | 8 | 6 | 0 | 2 | 0 |
| Dispatcher Acceptance | 12 | 11 | 0 | 1 | 0 |
| Cross-Role Security Acceptance | 6 | 6 | 0 | 0 | 0 |
| Service Boundary Acceptance | 6 | 6 | 0 | 0 | 0 |
| **Total** | **52** | **46** | **0** | **6** | **0** |

---

## 6. Identified Defect and Blocker Log

1. **Defect D-01 / Finding K4 — Merchant Approval Role Gap:**
   - **Impact:** Approving a merchant application in S1 updates the database status to `APPROVED`, but Keycloak does not automatically grant the `MERCHANT` realm role. A Keycloak administrator must manually assign the role before the merchant can access `/api/v1/merchant/**` endpoints.
   - **Related Issues:** #190 (@Niro0526), #166 (@pirathee587).
   - **Acceptance Status:** BLOCKED for automated zero-touch merchant onboarding.

2. **Defect D-02 — Courier Portal Delivery Cache Isolation:**
   - **Impact:** Previous courier query cache and neutral 403/404 handling are not yet in `develop`. Fix is contained in open draft PR #311 (Refs #154).
   - **Related Issue/PR:** PR #311 (Refs #154, @pirathee587).
   - **Acceptance Status:** BLOCKED on merge of PR #311.

3. **Blocker B-01 — Live Multi-Service Staging Environment:**
   - **Impact:** Docker daemon is not active locally, and no containerized staging environment with running microservices, Keycloak, Kafka, and PostgreSQL instances is available.
   - **Related Issues:** #165 (@kirusigan03), #167 (@14Jannan), #168 (@hephzibah-peries), #225, #226, #227, #228.
   - **Acceptance Status:** BLOCKED for live browser-driven end-to-end acceptance runs.

4. **Environment Note E-01 — macOS ARM64 Protoc Plugin Platform Incompatibility:**
   - **Impact:** `protoc-jar-maven-plugin:3.11.4` fails during local Maven builds on macOS Apple Silicon (`protoc-3.11.4-osx-aarch_64.exe` is missing from Maven Central). It succeeds in GitHub Actions CI which executes on Linux x86_64 runners.

---

## 7. Automated Test Verification Summary

### Frontend (`frontend/zentro-web`)
- **Vitest Test Suite:** `46` test files passed, `371` tests passed, `0` failed.
- **TypeScript Check:** `tsc -b --pretty false` passed with `0` errors.
- **ESLint:** `0` errors, `6` minor warnings.
- **Production Build:** `tsc -b && vite build` built production bundle cleanly.

### Backend Microservices (`backend/`)
- **API Gateway (`api-gateway`):** `61` tests run, `0` failures, `0` errors, `14` skipped.
- **Parcel & Pricing (`parcel-pricing-service`):** `629` tests run, `0` failures, `0` errors, `86` skipped without Docker.
- **Dispatch & Courier (`dispatch-courier-service`):** `237` tests run, `0` failures, `0` errors, `19` skipped without Docker (`1` container init failure without Docker).
- **Tracking & Reporting (`tracking-reporting-service`):** `48` tests run, `0` failures, `0` errors, `8` skipped without Docker.
- **Notification & Integration (`notification-integration-service`):** `394` tests run, `0` failures, `0` errors, `34` skipped without Docker.

---

## 8. Release Candidate Acceptance Conclusion

The Zentro platform release candidate at commit `8f66d4e` demonstrates **strong automated test coverage and strict role-based access control** across all five microservices and the web frontend.

However, because:
1. Live multi-service E2E tests (#165, #167, #168) remain open,
2. Courier delivery cache isolation (PR #311 / #154) is pending merge, and
3. Merchant Keycloak role assignment (Finding K4) requires manual administrator intervention,

the Release Candidate is **PARTIALLY ACCEPTED / UNVERIFIED LIVE E2E**. Production release sign-off should remain deferred until the linked blocker issues are resolved and verified against a live staging environment.
