# Price quotation API (ZD-15)

Service: S2 Parcel & Pricing (`parcel-pricing-service`, port 8082), reached through the API gateway on port 8080.  
Issue: #30. Pricing calculation dependency: #20 (ZD-05).

## 1. Endpoint

`POST /api/v1/parcels/estimate` (alias: `POST /api/v1/parcels/estimate-fee`)

| Item | Value |
|---|---|
| Roles | `CUSTOMER` or `DISPATCHER` (`@PreAuthorize("hasAnyRole('CUSTOMER', 'DISPATCHER')")`) |
| Forbidden | `MERCHANT`, `COURIER`, `SERVICE` (403) |
| Auth | `Authorization: Bearer <Keycloak access token>` |
| Content type | `application/json` |
| Side effects | Read-only. No parcel booking or outbox event is created, and no personal data is logged. |

The API Gateway routes `/api/v1/parcels/**` to S2 and allows `ROLE_CUSTOMER` and `ROLE_DISPATCHER`.  
Customer booking (`POST /api/v1/parcels`) and merchant order (`POST /api/v1/merchant/orders`) workflows invoke the same `PricingService.estimateFee` calculation in-process inside S2 as the single source of truth.

---

## 2. Request

```json
{
  "pickupCity": "Colombo",
  "deliveryCity": "Kandy",
  "destinationType": "OTHER_DISTRICT",
  "parcelSize": "MEDIUM",
  "weightKg": 2.0,
  "parcelCategory": "Electronics",
  "isExpress": false,
  "distanceKm": null,
  "lengthCm": null,
  "widthCm": null,
  "heightCm": null,
  "originZone": "ZONE_COLOMBO_METRO",
  "destinationZone": "ZONE_CENTRAL",
  "currency": "LKR"
}
```

| Field | Type | Rule |
|---|---|---|
| `weightKg` | `number` | Optional when `parcelSize` or full `lengthCm`/`widthCm`/`heightCm` dimensions are provided. Range: `0.1` to `20` kg, at most four decimal places. |
| `parcelSize` | `string` | Optional: `SMALL` (default 1.0 kg), `MEDIUM` (default 3.0 kg), `LARGE` (default 5.0 kg). Used when `weightKg` and dimensions are omitted. |
| `lengthCm`, `widthCm`, `heightCm` | `number` | Optional parcel dimensions in cm. Range: `0.01` to `500.00` cm each. If any dimension is provided, all three must be provided. Volumetric weight (`L * W * H / 5000`) cannot exceed `20` kg. |
| `destinationType` | `string` | Optional: `SAME_CITY`, `OTHER_DISTRICT`. Cannot be `SAME_CITY` when `originZone` and `destinationZone` are different zones. |
| `originZone`, `destinationZone` | `string` | Optional active delivery zone codes (`ZONE_COLOMBO_METRO`, `ZONE_WESTERN`, `ZONE_CENTRAL`, `ZONE_SOUTHERN`, `ZONE_NORTHERN`). If one is provided, both must be provided and non-blank. |
| `isExpress` | `boolean` | Optional (default `false`). Adds the express surcharge and sets `estimatedDays` to `1`. |
| `distanceKm` | `number` | Optional distance in km (`0.00` to `2000.00` km). |
| `pickupCity`, `deliveryCity` | `string` | Optional city names, at most 100 characters each. |
| `parcelCategory` | `string` | Optional category label, at most 100 characters. |
| `currency` | `string` | Optional ISO currency code (must match active rule currency `"LKR"` when provided). |

---

## 3. Pricing Rules & Money Handling

All calculations delegate to `PricingCalculationEngine.calculate(request, activeRule)` using exact `BigDecimal` arithmetic rounded to 2 decimal places with `RoundingMode.HALF_UP`:

1. **Chargeable weight:** `max(weightKg, volumetricWeight)` where `volumetricWeight = (lengthCm * widthCm * heightCm) / 5000` (scale 4, `HALF_UP`), falling back to `parcelSize.defaultWeightKg()` when neither `weightKg` nor dimensions are provided.
2. **Base fee (`breakdown.baseFee`):** `550.00` LKR (covers up to `1.00` kg `includedWeightKg`).
3. **Weight fee (`breakdown.weightFee`):** `max(0, chargeableWeight - 1.00) * 120.00` LKR, rounded to 2 decimal places (`HALF_UP`).
4. **Distance / zone surcharge (`breakdown.distanceSurcharge`):** `402.00` LKR when `destinationType == OTHER_DISTRICT` or `originZone != destinationZone` (or `distanceKm * perKmRate` when same-city and `perKmRate > 0`). Omitted from `breakdown` when `0.00`.
5. **Express fee (`breakdown.expressFee`):** `300.00` LKR when `isExpress == true`. Omitted from `breakdown` when `0.00`.
6. **Total (`fee`):** `baseFee + weightFee + distanceSurcharge + expressFee` (scale 2, `HALF_UP`).
7. **Estimated delivery (`estimatedDays`):** `1` day when `isExpress == true`, `3` days for cross-zone / `OTHER_DISTRICT`, `2` days for same-city / same-zone.

---

## 4. Response (`200 OK`)

```json
{
  "fee": 1072.00,
  "formattedFee": "LKR 1,072",
  "currency": "LKR",
  "estimatedDays": 3,
  "breakdown": {
    "baseFee": 550.00,
    "weightFee": 120.00,
    "distanceSurcharge": 402.00
  }
}
```

| Field | Type | Description |
|---|---|---|
| `fee` | `number` | Total delivery fee in `currency` (2 decimal places). |
| `formattedFee` | `string` | Human-readable total (e.g. `"LKR 550"`, `"LKR 1,072"`, `"LKR 551.20"`). |
| `currency` | `string` | Currency code (`"LKR"`). |
| `estimatedDays` | `integer` | Estimated transit time in days (`1`, `2`, or `3`). |
| `breakdown.baseFee` | `number` | Base delivery fee (`550.00`). |
| `breakdown.weightFee` | `number` | Additional weight charge above included weight (`0.00` or higher). |
| `breakdown.distanceSurcharge` | `number` | Present when non-zero (`402.00` for `OTHER_DISTRICT` or cross-zone). |
| `breakdown.expressFee` | `number` | Present when non-zero (`300.00` when `isExpress` is `true`). |

---

## 5. Errors (RFC 7807 `application/problem+json`)

All error responses use `application/problem+json` without stack traces, internal class names, or personal data. Validation errors include `fieldErrors` (and `invalidFields`) keyed by field name so UI forms can map messages directly to inputs.

| Status | When |
|---|---|
| `400 Bad Request` | Missing weight/size/dimensions, malformed JSON, non-numeric or out-of-range values, negative or zero weight/dimensions, partial dimensions, unknown `parcelSize`/`destinationType`, unknown or blank `originZone`/`destinationZone`, single zone without its pair, conflicting `SAME_CITY` with cross-zone route, or unsupported currency. |
| `401 Unauthorized` | Missing, malformed, or expired Bearer token. |
| `403 Forbidden` | Authenticated user does not have `ROLE_CUSTOMER` or `ROLE_DISPATCHER`. |
| `503 Service Unavailable` | Pricing rule or zone configuration is unavailable or invalid. Never returns a zero or partial price. |

### Example `400 Bad Request` (field validation)

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "Validation failed for request.",
  "instance": "/api/v1/parcels/estimate",
  "fieldErrors": {
    "weightKg": "Weight must be between 0.1 and 20 kg with at most 4 decimal places."
  },
  "invalidFields": {
    "weightKg": "Weight must be between 0.1 and 20 kg with at most 4 decimal places."
  }
}
```

### Example `503 Service Unavailable`

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Pricing service is temporarily unavailable. Please try again later.",
  "instance": "/api/v1/parcels/estimate"
}
```

---

## 6. Consumers: Dispatcher "Zones & pricing" page (ZD-19, #34)

Screen: `/dispatcher/zones-pricing` in the Dispatcher portal (`frontend/zentro-web/src/portals/dispatcher/pages/ZonesPricingPage.tsx`). Only the `DISPATCHER` route guard lets users in; the backend still checks the role on every call.

| Section | API | What the page does |
|---|---|---|
| Pricing rule | `GET /api/v1/admin/pricing-rules`, `PUT /api/v1/admin/pricing-rules` | Shows the live rule and lets a dispatcher edit the six amounts. Each amount is required, strictly bounded, and has at most 2 decimals. Range boundaries enforced: `baseFee` (0.01 to 1,000,000.00), `includedWeightKg` (0.01 to 100.00), `additionalWeightRatePerKg` (0.00 to 1,000,000.00), `distanceSurchargeOtherDistrict` (0.00 to 1,000,000.00), `expressSurcharge` (0.00 to 1,000,000.00), `perKmRate` (0.00 to 100,000.00). Currency must be `"LKR"` (defaults to `"LKR"` if omitted). Save shows a confirmation with the old and new values. On `400` the `fieldErrors` / `invalidFields` messages appear next to the fields; `401`/`403`, `503` and network errors show an error message. |
| Quotation preview | `POST /api/v1/parcels/estimate` | Sends destination type, parcel size, weight (0.01-100 kg), express and optional origin/destination zones. Shows the fee, estimated days and each breakdown line the API returns. The page never calculates a price. |
| Delivery zones | `GET /api/v1/zones`, `GET /api/v1/zones/{code}` | Read-only delivery zone retrieval (all zones or single zone by zone code with 404 if not found). |

**Money format:** every amount is shown with two decimals and the currency from the API, for example `LKR 1,072.00`, using `fee` and the breakdown numbers. `formattedFee` is not used because it drops the decimals.

**Current limits:**
- Zones cannot be added or edited: `/api/v1/admin/zones/**` is routed by the gateway but has no backend write endpoint.
- When the pricing database cannot be read, `GET /api/v1/admin/pricing-rules` returns the default rule, so the page cannot tell defaults from saved values (owner @Jathuja).

## Validation hardening (#124): deliberate contract tightening

The actual-weight range changes from 0.01–100 kg to **0.1–20 kg** on both aliases, sharing the booking/merchant validator. Decimal JSON numbers allow at most four decimal places; numeric strings and exponent notation return safe 400 errors. Dimensions and distance allow at most four decimal places. The existing volumetric ceiling changes from 100 to 20 kg, checked before rounding; exceeding it sets errors on `lengthCm`, `widthCm`, and `heightCm`. No price formula changed.

Dimension-based quotes are informational: neither booking nor merchant order DTO accepts dimensions, and unknown booking fields are ignored. A dimension quote cannot currently be carried into booking with the same dimensions. See the shared [validation rules and evidence](s2-validation.md). Error status remains 400 with flat `fieldErrors` and `invalidFields`; booking/order remain 422 with request-path keys. Zero distance remains valid under the existing contract.
