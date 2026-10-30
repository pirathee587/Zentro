# Parcel booking API (ZD-03)

Service: S2 Parcel & Pricing (`parcel-pricing-service`, port 8082), reached through the API gateway on port 8080.
Issue: #18. Pricing dependency: #20 (ZD-05).

## Endpoint

`POST /api/v1/parcels`

| Item | Value |
|---|---|
| Role | `CUSTOMER` only (`@PreAuthorize("hasRole('CUSTOMER')")`) |
| Auth | `Authorization: Bearer <Keycloak access token>` |
| Content type | `application/json` |
| Optional header | `Idempotency-Key`: 1 to 100 characters |
| Booking owner | The token `sub` claim. The body cannot set the customer. |

The gateway already routes `/api/v1/parcels/**` to S2 and allows `ROLE_CUSTOMER` and `ROLE_DISPATCHER`. S2 then accepts only `CUSTOMER` on this endpoint.

## Request

The body matches the frontend `CreateDeliveryRequest` type.

```json
{
  "sender":    { "name": "Sender Contact", "phone": "0771234567",    "address": "12 Galle Road, Colombo 03", "city": "Colombo" },
  "recipient": { "name": "Recipient Name", "phone": "+94 77 123 4567", "address": "45 Temple Street, Kandy",   "city": "Kandy" },
  "parcel":    { "weightKg": 2.0, "category": "Small Box", "size": "SMALL", "description": "Books" },
  "destinationType": "OTHER_DISTRICT",
  "isExpress": false,
  "notes": "Call before delivery"
}
```

| Field | Rule |
|---|---|
| `sender`, `recipient`, `parcel` | Required objects |
| `*.name` | Required, at most 100 characters |
| `*.phone` | Required. `^(\+94\|0)\d{9}$` after removing spaces and dashes. Stored without separators. |
| `*.address` | Required, 8 to 255 characters (leading and trailing spaces are not counted) |
| `*.city` | Optional, at most 100 characters |
| `parcel.weightKg` | Required, 0.1 to 20. Stored with 2 decimals. |
| `parcel.category` | Required, at most 40 characters |
| `parcel.size` | Optional: `SMALL`, `MEDIUM`, `LARGE` |
| `parcel.description` | Optional, at most 255 characters |
| `destinationType` | Optional: `SAME_CITY`, `OTHER_DISTRICT` |
| `isExpress` | Optional boolean (default `false`) |
| `notes` | Optional, at most 500 characters |

The server ignores `fee`, `status`, `trackingRef`, `customerId` and any other unknown fields. The server always calculates the fee.

## Response

`201 Created` with `Location: /api/v1/parcels/{id}`. A replayed idempotent request returns `200 OK` with the same body and no `Location` header.

```json
{
  "id": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
  "trackingRef": "ZT-7K3QX9M2PA",
  "status": "CREATED",
  "fee": 1072.00,
  "currency": "LKR",
  "estimatedDays": 3,
  "createdAt": "2026-10-28T10:15:30Z",
  "sender":    { "name": "Sender Contact", "phone": "0771234567",   "address": "12 Galle Road, Colombo 03", "city": "Colombo" },
  "recipient": { "name": "Recipient Name", "phone": "+94771234567", "address": "45 Temple Street, Kandy",   "city": "Kandy" },
  "parcel":    { "weightKg": 2.00, "category": "Small Box", "size": "SMALL", "description": "Books" }
}
```

`trackingRef` is `ZT-` followed by 10 random Crockford Base32 characters (`SecureRandom`). References are not sequential and cannot be guessed. The database enforces uniqueness, and the service retries up to 3 times on a collision.

`GET /api/v1/parcels/{id}` is not part of this task. The `Location` header points to where that endpoint will live.

## Errors

All errors use `application/problem+json` (RFC 9457). Messages never include names, phone numbers or addresses.

| Status | When |
|---|---|
| 400 | Malformed JSON, an unknown enum value, or an `Idempotency-Key` that is blank or longer than 100 characters |
| 401 | Missing or invalid token |
| 403 | Authenticated without the `CUSTOMER` role (MERCHANT, COURIER, DISPATCHER, SERVICE) |
| 409 | `Idempotency-Key` already used by this customer with a different request body |
| 422 | Field validation failed, or pricing rejected the parcel data. The response includes `fieldErrors` keyed by request path. |
| 503 | Pricing failed, or the booking could not be saved. Nothing is stored. Safe to retry with the same `Idempotency-Key`. |

Example 422:

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": "Some booking details are missing or invalid.",
  "instance": "/api/v1/parcels",
  "fieldErrors": {
    "recipient.phone": "Enter a Sri Lankan number, e.g. 0771234567 or +94771234567.",
    "parcel.weightKg": "Weight must be between 0.1 and 20 kg."
  }
}
```

## Idempotency

- The key is unique per customer: `UNIQUE (customer_subject, idempotency_key)`.
- The server stores a SHA-256 hash of the normalised request. Text is trimmed, phone separators are removed and the weight is rounded to 2 decimals.
- Same key and same body: the original booking is returned with `200`, and nothing new is priced or saved.
- Same key and a different body: `409`.
- No header: every request creates a new booking.
- Two concurrent requests with the same key: the second insert hits the unique constraint, and the service returns the winning booking.

## Pricing dependency

The booking calls `PricingService.estimateFee` in-process with:
`pickupCity = sender.city`, `deliveryCity = recipient.city`, `destinationType`, `parcelSize = parcel.size`,
`weightKg`, `parcelCategory = parcel.category`, `isExpress`, `currency = "LKR"`. Distance, dimensions and zones are not sent.

- `IllegalArgumentException` from pricing returns 422.
- Any other failure returns 503 with `PricingUnavailableException`.
- In both cases no row is written. The service prices first and then inserts in its own transaction.

Known risk (owner: pricing, ZD-05): `PricingService.getActiveRule()` falls back to the default rates when the pricing table cannot be read. A database outage can therefore still produce a fee from the defaults.

## Storage

Migration `V2__create_parcel_booking.sql` creates schema `parcel` and table `parcel.parcel`. The table has a status check (`CREATED`, `PICKED_UP`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED`, `FAILED`), a unique `tracking_ref`, and a unique `(customer_subject, idempotency_key)`. New bookings start as `CREATED`. The table also stores `estimated_days` so a replay returns the same response.

## Open item for the frontend (ZD-21, #56)

`frontend/zentro-web/src/portals/customer/services/customerApi.ts` posts bookings to
`${VITE_CUSTOMER_API_BASE_URL or /api/v1/customer}/deliveries`. The gateway does not route `/api/v1/customer/**`, and it denies all `/api/v1/**` paths it does not list.
The frontend must post to `/api/v1/parcels`. It must also map the response: `status` is `CREATED` (not `Created`), and there is no `formattedFee` or `timeline`.
