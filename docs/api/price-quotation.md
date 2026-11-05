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
| `weightKg` | `number` | Optional when `parcelSize` or full `lengthCm`/`widthCm`/`heightCm` dimensions are provided. Range: `0.01` to `100.00` kg. |
| `parcelSize` | `string` | Optional: `SMALL` (default 1.0 kg), `MEDIUM` (default 3.0 kg), `LARGE` (default 5.0 kg). Used when `weightKg` and dimensions are omitted. |
| `lengthCm`, `widthCm`, `heightCm` | `number` | Optional parcel dimensions in cm. Range: `0.01` to `500.00` cm each. If any dimension is provided, all three must be provided. Volumetric weight (`L * W * H / 5000`) cannot exceed `100.00` kg. |
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
    "weightKg": "Weight cannot exceed 100 kg"
  },
  "invalidFields": {
    "weightKg": "Weight cannot exceed 100 kg"
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

