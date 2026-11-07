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

The gateway routes `/api/v1/parcels/**` to S2. `POST /api/v1/parcels` requires `ROLE_CUSTOMER` at both gateway and S2; dispatcher access is not allowed for booking.

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
- The server stores a SHA-256 hash of deterministic canonical JSON and its internal fingerprint version. Text is trimmed and phone separators are removed.
- New keyed bookings use v2: the fingerprint retains full numeric weight precision, with trailing zeros removed. `2.550` and `2.55` replay identically; `2.551` and `2.554` conflict. Pricing still receives the original weight, and stored parcel weight remains rounded to 2 decimals.
- Historical bookings retain v1: weight is rounded to 2 decimals for comparison. Their original precision cannot be recovered, so historical keys keep that compatibility behavior. Replays never rewrite hashes or versions.
- Same key and same body: the original booking is returned with `200`, and nothing new is priced or saved.
- Same key and a different body: `409`.
- No header: every request creates a new booking.
- Two concurrent requests with the same key: the second insert hits the unique constraint; comparison uses the winning booking's stored version and returns its booking or `409` for conflicting content.

Migration `V8__add_booking_fingerprint_version.sql` adds internal version metadata, defaulting existing rows and no-key/merchant inserts to v1. The customer/key unique constraint is unchanged. No public request or response field is added. Deploy the migration before the new service and drain old S2 writers before enabling v2 writes: old binaries cannot interpret v2 hashes. Downgrading to an old binary after v2 writes requires an explicit compatibility plan; do not rehash existing rows.

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

## Validation hardening (#124)

See the shared [validation rules and boundary evidence](s2-validation.md). Addresses use 8–255 Unicode code points after trimming and reject controls/format characters before trimming. Text is otherwise preserved (including HTML-looking content); output encoding is the consumer's responsibility. Weight accepts decimal JSON numbers with at most four decimal places; strings and exponent notation return safe 400 errors. Stored weight remains rounded to two decimals and existing idempotency fingerprint behavior is unchanged. A size label is not a validated weight tier: 15 kg SMALL is permitted and charged for 15 kg.
