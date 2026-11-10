# Merchant Order API (ZD-08)

Service: S2 Parcel & Pricing (`parcel-pricing-service`, port 8082), reached through the API gateway on port 8080.  
Issue: #23. Related: #17 (Merchant Registration), #27 (Merchant Approval), #62 (API Keys), #32 (Webhooks).

## 1. Endpoints

### 1.1 Create or Replay Merchant Order
`POST /api/v1/merchant/orders`

| Item | Value |
|---|---|
| Role | `MERCHANT` only (`@PreAuthorize("hasRole('MERCHANT')")`) |
| Approval | Requires `APPROVED` merchant application and `ACTIVE` account status verified via Identity (S1) |
| Auth | `Authorization: Bearer <Keycloak access token>` |
| Content type | `application/json` |
| Idempotency | Determined by `externalOrderRef` (scoped per merchant) |
| Order owner | Strictly resolved from the token `sub` claim. Client cannot forge or override `merchantId`. |

The gateway routes `/api/v1/merchant/orders/**` to S2 and restricts `/api/v1/merchant/**` to `ROLE_MERCHANT`. S2 enforces local role checks and actively verifies merchant approval with S1.

### 1.2 Get Order by ID

`GET /api/v1/merchant/orders/{id}`

`id` is the order UUID, not the tracking reference or external order reference. No request body is required. Success returns `200 OK` with one `MerchantOrderResponse` object (Section 3.3), using `application/json`.

Requests are strictly scoped to the authenticated merchant; an unknown order or another merchant's order ID returns `404 Not Found` with detail `Order not found.` to prevent resource enumeration. Both this endpoint and the list endpoint require the bearer token, merchant role, approval and active-account checks in Section 5.

### 1.3 List Merchant Orders

`GET /api/v1/merchant/orders?limit={limit}`

No request body is required. The only supported query parameter is integer `limit`, defaulting to 50. The controller clamps parsed values to 1 through 100: values below 1 become 1 and values above 100 become 100.

Success returns `200 OK` with an `application/json` array of `MerchantOrderResponse` objects (the same complete DTO as the detail endpoint). No orders returns `[]`. Results include only the authenticated merchant's orders, ordered by `createdAt DESC`; no secondary ordering is specified for equal timestamps.

This is a bounded recent-order list, not a paginated response. It has no total count, page, offset, cursor, search or status-filter contract. A full result array does not establish whether more orders exist.

---

## 2. Request Contract (`POST /api/v1/merchant/orders`)

```json
{
  "externalOrderRef": "ORD-2026-11-9876",
  "sender": {
    "name": "Apex Electronics",
    "phone": "0112345678",
    "address": "77 Galle Road, Colombo 03",
    "city": "Colombo"
  },
  "recipient": {
    "name": "Nimal Perera",
    "phone": "+94 77 123 4567",
    "address": "15 Peradeniya Road, Kandy",
    "city": "Kandy"
  },
  "parcel": {
    "weightKg": 1.5,
    "category": "Electronics",
    "size": "MEDIUM",
    "description": "Wireless headphones"
  },
  "destinationType": "OTHER_DISTRICT",
  "isExpress": false,
  "notes": "Fragile electronic device"
}
```

| Field | Validation Rule |
|---|---|
| `externalOrderRef` | Required string, 1 to 100 characters. Trimmed. Also accepts `external_order_ref`. |
| `sender`, `recipient`, `parcel` | Required objects |
| `*.name` | Required, at most 100 characters |
| `*.phone` | Required. Sri Lankan format: `^(\+94\|0)\d{9}$` after stripping spaces and dashes. |
| `*.address` | Required, 8 to 255 characters (excluding leading and trailing spaces) |
| `*.city` | Optional, at most 100 characters |
| `parcel.weightKg` | Required, 0.1 to 20 kg. Stored and priced rounded to 2 decimals. |
| `parcel.category` | Required, at most 40 characters |
| `parcel.size` | Optional: `SMALL`, `MEDIUM`, `LARGE` |
| `parcel.description` | Optional, at most 255 characters |
| `destinationType` | Optional: `SAME_CITY`, `OTHER_DISTRICT` |
| `isExpress` | Optional boolean (default `false`) |
| `notes` | Optional, at most 500 characters |

---

## 3. Response Contract

### 3.1 New Order (`201 Created`)
Returns `201 Created` with header `Location: /api/v1/merchant/orders/{id}`.

```json
{
  "id": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
  "trackingRef": "ZT-7K3QX9M2PA",
  "externalOrderRef": "ORD-2026-11-9876",
  "merchantId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "status": "CREATED",
  "fee": 670.00,
  "currency": "LKR",
  "estimatedDays": 2,
  "createdAt": "2026-11-03T10:15:30Z",
  "sender": {
    "name": "Apex Electronics",
    "phone": "0112345678",
    "address": "77 Galle Road, Colombo 03",
    "city": "Colombo"
  },
  "recipient": {
    "name": "Nimal Perera",
    "phone": "+94771234567",
    "address": "15 Peradeniya Road, Kandy",
    "city": "Kandy"
  },
  "parcel": {
    "weightKg": 1.50,
    "category": "Electronics",
    "size": "MEDIUM",
    "description": "Wireless headphones"
  }
}
```

### 3.2 Idempotent Replay (`200 OK`)
When the same merchant submits the same `externalOrderRef` with identical order details:
- Returns `200 OK` with the existing order payload.
- No `Location` header is returned.
- No duplicate parcel is created, and no additional pricing calculation occurs.

### 3.3 Shared Read Response (`MerchantOrderResponse`)

List entries, detail reads, creation and replay use the same S2 DTO. The example in Section 3.1 also illustrates a detail response; the list wraps such objects in a JSON array without a response envelope.

| Field | JSON type | Nullable | Meaning |
|---|---|---|---|
| `id` | string (UUID) | No | Order identifier used in the detail path |
| `trackingRef` | string | No | Parcel tracking reference |
| `externalOrderRef` | string | No | Merchant's external order reference |
| `merchantId` | string | No | Merchant subject derived from JWT `sub`; not necessarily a UUID |
| `status` | string (enum) | No | Current persisted parcel status; see Section 7 |
| `fee` | number | No | Stored fee, represented by Java `BigDecimal` |
| `currency` | string | No | Stored currency code |
| `estimatedDays` | integer | No | Stored delivery estimate in days |
| `createdAt` | string (offset date-time) | No | Creation timestamp, for example `2026-11-03T10:15:30Z` |
| `sender` | object (`Contact`) | No | Sender details |
| `recipient` | object (`Contact`) | No | Recipient details |
| `parcel` | object (`Parcel`) | No | Parcel details |

Both `sender` and `recipient` have exactly these fields:

| Field | JSON type | Nullable |
|---|---|---|
| `name` | string | No |
| `phone` | string | No |
| `address` | string | No |
| `city` | string | Yes |

The `parcel` object has exactly these fields:

| Field | JSON type | Nullable |
|---|---|---|
| `weightKg` | number (Java `BigDecimal`) | No |
| `category` | string | No |
| `size` | string: `SMALL`, `MEDIUM`, `LARGE` | Yes |
| `description` | string | Yes |

Consumers must handle null cities, size and description. These responses do not include `updatedAt`, status history, failure reason, notes, express flag or destination type. Do not infer those fields from the creation request or a separate tracking response.

---

## 4. Idempotency & Duplicate Handling

- **Scope:** Uniqueness is strictly enforced per merchant: `UNIQUE (merchant_id, external_order_ref)`. Different merchants may use the same `externalOrderRef` without collision.
- **Request Hash:** A canonical SHA-256 hash of the normalized request is stored.
- **Identical Repeat:** Returns `200 OK` with original order.
- **Conflict:** If a merchant submits an existing `externalOrderRef` with different details, the API returns `409 Conflict`.
- **Concurrency:** Concurrent requests with the same reference are arbitrated by the database unique constraint; the second insert fails safely and replays the winner.

### 4.1 Events (#32)

A new order (`201 Created`) writes one `parcel.created` outbox event in the same transaction as the order, with `source: MERCHANT_ORDER` and the merchant account id in `payload.merchantId`. Replays (`200 OK`) and conflicts (`409`) write no event. Later status changes of the parcel carry the same `merchantId` in `parcel.status.updated`. No names, phone numbers, addresses or the external order reference are put in events. See [parcel-events.md](parcel-events.md) and [merchant-webhooks.md](merchant-webhooks.md).

---

## 5. Security & Merchant Approval Enforcement

These checks apply to creation, list and detail operations. Portal calls use bearer authentication through the gateway; an API-key secret is not required for these browser reads. Gateway validation does not replace S2's independent JWT validation and method authorization.

1. **Authentication:** Bearer token from Keycloak is required (`401 Unauthorized` if missing/invalid).
2. **Role Authorization:** Caller must have `ROLE_MERCHANT` (`403 Forbidden` for other roles).
3. **Approval Status:** `IdentityMerchantClient` checks with S1 (`GET /api/v1/users/me`):
   - Merchant application must be `APPROVED`.
   - User account status must be `ACTIVE`.
   - If pending, rejected, or inactive, returns `403 Forbidden`.
4. **Scoping:** The merchant subject is extracted directly from the verified token `sub` claim; a missing or blank subject returns `401`. No client-supplied merchant ID selects the owner. The list repository filters by `merchant_id`; detail lookup filters by both order ID and `merchant_id`. Cross-merchant access is prevented on the backend, regardless of frontend route guards.
5. **Identity failures:** S1's 401/403 responses remain 401/403. An empty profile, transport failure or other upstream HTTP failure becomes `503 Service Unavailable`; verification fails closed.

---

## 6. Errors (RFC 7807)

All errors return `application/problem+json`:

| Status | When |
|---|---|
| 400 | Malformed JSON or invalid enum value |
| 401 | Missing, malformed, or expired access token |
| 403 | Missing `MERCHANT` role, unapproved merchant application, or inactive user account |
| 404 | Order ID does not exist or belongs to another merchant |
| 409 | `externalOrderRef` already exists for this merchant with different details |
| 422 | Field validation failed (e.g. invalid phone, weight out of bounds). Includes `fieldErrors` map. Personal data is never echoed in error messages. |
| 503 | Pricing service unavailable, identity service unreachable, or database storage failure. Nothing is saved; safe to retry. |

### 6.1 List and Detail Read Errors

The creation-specific 409/422 cases above are not part of the normal list/detail read flow. Read callers should handle:

| Status | Read behavior |
|---|---|
| 400 | Invalid parameter conversion, such as a non-integer `limit` or invalid UUID `id` |
| 401 | Missing, invalid or expired bearer authentication; missing token subject; S1 authentication rejection |
| 403 | Missing merchant role, unapproved merchant, inactive account or S1 access rejection |
| 404 | Detail order is absent or owned by another merchant; both use `Order not found.` |
| 503 | Identity verification is unavailable or S2 database access fails |

`MerchantOrderExceptionHandler` maps explicit status exceptions and database failures to problem details with `type`, `title`, `status` and `detail`. Its database error detail currently says `The order could not be saved right now. Please try again.` even for a read failure; clients should use the HTTP status and a suitable read-error message rather than assume a write occurred. No order data should be fabricated from an error. Gateway errors such as rate limiting are separate from these controller responses.

## 7. Parcel Status Snapshots and Limitations

| Value | Meaning |
|---|---|
| `CREATED` | Order registered; initial state |
| `PICKED_UP` | Parcel picked up |
| `IN_TRANSIT` | Parcel in transit |
| `OUT_FOR_DELIVERY` | Parcel out for delivery |
| `DELIVERED` | Delivery completed; terminal state |
| `FAILED` | Delivery failed; terminal state |

List and detail return the status stored by S2 at the time of the read. They provide no status-change timestamp, event history, failure reason, live connection or automatic refresh guarantee. A later read may return a newer status; clients must not simulate lifecycle progress or duplicate backend transition rules.

These endpoints do not support pagination, live SSE tracking or merchant-initiated status mutation. S4 tracking is a separate contract and projection dependency; this document does not establish its readiness or change its ownership. See [tracking-history.md](tracking-history.md) and [parcel-status.md](parcel-status.md) for the separate tracking and authorized status-update contracts.

## 8. Proposed Issue #153 Frontend Integration Boundary

For [Issue #153](https://github.com/pirathee587/Zentro/issues/153), the proposed foundation is typed, authenticated list/detail reads using the existing S2 endpoints, with cancellation, principal-scoped caching and safe error propagation. This is a proposal for coordination with [Issue #238](https://github.com/pirathee587/Zentro/issues/238), not an agreed reassignment of either issue's acceptance criteria or ownership.

- Keep merchant operations and DTOs in `frontend/zentro-web/src/portals/merchant/api/merchantApi.ts`. Reuse `MerchantOrderStatus`, `MerchantOrder`, `MerchantApiAuth`, the existing authentication helper and `getMerchantOrders()`. The current frontend order type covers summary fields only; future detail integration must represent the nested fields and nullability above.
- Reuse `useMerchantOrdersQuery()` in `portals/merchant/hooks/useMerchantQueries.ts`, shared query-key factories and `principalCacheScope`. A future detail query must distinguish the order ID and principal; propagate TanStack Query's `AbortSignal` to the existing Axios client.
- Use the centralized `shared/api/apiClient.ts`, existing bearer-token flow, error normalization and singleton query defaults. Do not introduce another client, log private order data or automatically replay rejected 401/403 requests. See [frontend-api.md](../development/frontend-api.md).
- Coordinate detail pages, routes, navigation, list refactoring, presentation, tracking links and pagination with #238 before changing overlapping UI. This documentation commit assigns none of those features and implements no frontend behavior.
- Keep this foundation independent of S4 projection fixes, SSE, database/schema changes, new endpoints and merchant status mutation. Preserve all five service boundaries and database-per-service ownership.

Existing tests to preserve and reuse include frontend `merchantApi.test.ts` (authenticated list and authorization failures), `DashboardPage.test.tsx` (loading, empty, error/retry and status display), shared API/query-key tests, and backend `MerchantOrderControllerTest`, `MerchantOrderServiceTest`, `MerchantOrderIntegrationTest`, `IdentityMerchantClientResilienceTest` and `MerchantOrderOutboxAtomicityIntegrationTest`. Preserve their authorization, ownership, failure and transaction coverage when later adding detail integration; this documentation update does not claim new test execution or issue completion.
