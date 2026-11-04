# Tracking History API (ZD-16)

Service: S4 Tracking & Reporting (`tracking-reporting-service`, port 8084).  
Issue: #31.  
Related: #28 parcel status management, #58 event publishing, #61 delivery history UI (ZD-26).

---

## Overview

The Tracking History service provides real-time parcel tracking queries and chronological milestone history for both public inquiries (privacy-preserving) and authenticated customers (`/mine`). It consumes domain events (`parcel.created`, `parcel.status.updated`) asynchronously from Kafka while providing local/direct fallback backfill from `parcel.parcel` for development and testing environments.

---

## Endpoints

### 1. Public Parcel Tracking

`GET /api/v1/tracking/{trackingRef}`

Provides high-level tracking status for public tracking portals. Strictly protects customer privacy: excludes sender/recipient full names, phone numbers, complete street addresses (only city is surfaced), and financial fee information.

| Item | Value |
|---|---|
| Roles | Public (No authentication required) |
| Content type | `application/json` |
| Tracking Ref | Alphanumeric tracking code (e.g., `ZT-12345678`, `PKG-001`) |

#### Response `200`

```json
{
  "trackingRef": "ZT-ABCD1234",
  "status": "IN_TRANSIT",
  "statusDisplay": "In Transit",
  "originCity": "Colombo",
  "destinationCity": "Kandy",
  "estimatedDelivery": "2026-11-05T14:00:00Z",
  "currentLocation": "Central Sorting Hub",
  "latestUpdate": "2026-11-04T12:00:00Z",
  "parcelType": "EXPRESS"
}
```

---

### 2. Public Tracking Event Milestones & Live Stream

`GET /api/v1/tracking/{trackingRef}/events`

Retrieves the chronological list of milestone tracking events for a parcel. Also supports Server-Sent Events (`text/event-stream`) for live updates.

| Item | Value |
|---|---|
| Roles | Public (No authentication required) |
| Content type | `application/json` or `text/event-stream` |

#### Response `200` (application/json)

```json
[
  {
    "eventId": "e1a2b3c4-0000-0000-0000-000000000001",
    "status": "CREATED",
    "title": "Parcel Booked",
    "description": "Delivery request created and confirmed",
    "location": "Colombo Dispatch Center",
    "occurredAt": "2026-11-04T08:00:00Z"
  },
  {
    "eventId": "e1a2b3c4-0000-0000-0000-000000000002",
    "status": "IN_TRANSIT",
    "title": "In Transit",
    "description": "Package is on the way to destination hub",
    "location": "Central Sorting Hub",
    "occurredAt": "2026-11-04T12:00:00Z"
  }
]
```

---

### 3. Authenticated Customer Tracking History

`GET /api/v1/tracking/mine`

Retrieves the authenticated customer's parcels with detailed sender/recipient contact information, fees, current status, and full milestone timeline.

| Item | Value |
|---|---|
| Roles | `ROLE_CUSTOMER`, `ROLE_MERCHANT` |
| Auth | `Authorization: Bearer <Keycloak access token>` |
| Actor Isolation | Uses JWT subject (`sub`) as `customerId`. Client-supplied customer identifiers are ignored. |
| Query Parameters | `page` (default: 0), `size` (default: 20), `status` (optional filter), `q` (optional keyword search on trackingRef, recipient name, or destination city) |

#### Response `200`

```json
{
  "content": [
    {
      "id": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
      "trackingRef": "ZT-ABCD1234",
      "status": "IN_TRANSIT",
      "statusDisplay": "In Transit",
      "sender": {
        "name": "John Doe",
        "phone": "+94770000001",
        "city": "Colombo",
        "address": "123 Galle Road, Colombo 03"
      },
      "recipient": {
        "name": "Jane Smith",
        "phone": "+94770000002",
        "city": "Kandy",
        "address": "45 Peradeniya Road, Kandy"
      },
      "parcel": {
        "type": "Standard",
        "weightKg": 2.5,
        "fee": 750.00,
        "currency": "LKR"
      },
      "timeline": [
        {
          "status": "CREATED",
          "title": "Parcel Booked",
          "description": "Delivery request created and confirmed",
          "timestamp": "2026-11-04T08:00:00Z"
        },
        {
          "status": "IN_TRANSIT",
          "title": "In Transit",
          "description": "Package is on the way to destination hub",
          "timestamp": "2026-11-04T12:00:00Z"
        }
      ],
      "estimatedDelivery": "2026-11-05T14:00:00Z",
      "createdAt": "2026-11-04T08:00:00Z",
      "updatedAt": "2026-11-04T12:00:00Z"
    }
  ],
  "parcels": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1,
  "last": true
}
```

*Note: Both `content` (Spring standard) and `parcels` (frontend normalization fallback) are populated for maximum backward and forward frontend compatibility.*

---

## Kafka Event Consumption

S4 listens to Kafka topics published by S2 Parcel & Pricing:

| Topic | Key | Payload | Action |
|---|---|---|---|
| `parcel.created` | `parcelId` | `ParcelCreatedEvent` | Upserts `tracking_parcel` and appends `CREATED` milestone event. |
| `parcel.status.updated` | `parcelId` | `ParcelStatusUpdatedEvent` | Updates current status on `tracking_parcel`, inserts milestone in `tracking_event`, and notifies connected SSE listeners. |

### At-Least-Once Delivery & Idempotency
- Incoming events are checked against `tracking.processed_event` using `(consumer_name, event_id)`.
- If an event with the same ID was previously processed, it is safely acknowledged and discarded.

---

## Errors

All error responses adhere to RFC 7807/9457 `ProblemDetail`:

| Status | `code` | Description |
|---|---|---|
| 400 | `INVALID_TRACKING_REF` | Empty or malformed tracking reference. |
| 401 | (security) | Missing, expired, or invalid Bearer token. |
| 403 | (security) | Insufficient permissions (not a customer or merchant). |
| 404 | `TRACKING_NOT_FOUND` | Parcel with the requested tracking reference does not exist. |
| 500 | `INTERNAL_SERVER_ERROR` | Unexpected backend or database exception. |
