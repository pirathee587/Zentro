# Parcel status API (ZD-13)

Service: S2 Parcel & Pricing (`parcel-pricing-service`, port 8082).
Issue: #28. Decision: S2 is the single writer of parcel status (Option A, recorded on #28).
Related: #58 event publishing, #60 role-specific restrictions, #59 proof of delivery (S3), #31 tracking history (S4).

## Lifecycle

`ParcelLifecycle` is the only place these rules are defined.

| From | Allowed next |
|---|---|
| `CREATED` | `PICKED_UP` |
| `PICKED_UP` | `IN_TRANSIT`, `FAILED` |
| `IN_TRANSIT` | `OUT_FOR_DELIVERY`, `FAILED` |
| `OUT_FOR_DELIVERY` | `DELIVERED`, `FAILED` |
| `DELIVERED` | none (terminal) |
| `FAILED` | none (terminal) |

- Requesting the current status again is an idempotent success: `200` with `changed: false`, no history row and no event.
- Every other change is rejected with `409`.
- `FAILED` requires a reason.
- Role-specific restrictions (ZD-25 / #60) are enforced on top of these rules and can only narrow them.

### Role Transition Matrix (ZD-25)

| Current Status | Target Status | DISPATCHER | SERVICE | COURIER | Reason / Rule |
|---|---|---|---|---|---|
| `CREATED` | `PICKED_UP` | ✅ Allowed | ❌ Denied (409) | ❌ Denied (403) | Operational pickup record. |
| `PICKED_UP` | `IN_TRANSIT` | ✅ Allowed | ❌ Denied (409) | ❌ Denied (403) | Linehaul transit update. |
| `PICKED_UP` | `FAILED` | ✅ Allowed | ❌ Denied (409) | ❌ Denied (403) | Pickup failure (requires non-empty `reason`). |
| `IN_TRANSIT` | `OUT_FOR_DELIVERY` | ✅ Allowed | ❌ Denied (409) | ❌ Denied (403) | Local hub dispatch for delivery. |
| `IN_TRANSIT` | `FAILED` | ✅ Allowed | ❌ Denied (409) | ❌ Denied (403) | Transit loss/damage (requires non-empty `reason`). |
| `OUT_FOR_DELIVERY` | `DELIVERED` | ✅ Allowed | ✅ Via authenticated `CompleteParcelDelivery` gRPC | ❌ Denied (403) | Successful delivery. S3 POD completion is the only service path. |
| `OUT_FOR_DELIVERY` | `FAILED` | ✅ Allowed | ❌ Denied (409) | ❌ Denied (403) | Delivery attempt failure (requires non-empty `reason`). |
| Any Status | *Same Status* | ✅ Allowed (`changed: false`) | ✅ `DELIVERED` only over gRPC | ❌ Denied (403) | Idempotent repeat. SERVICE may repeat `DELIVERED` only. |
| `DELIVERED` / `FAILED` | *Any Status* | ❌ Terminal (409) | ❌ Terminal (409) | ❌ Denied (403) | Terminal states reject any further change. |

- **DISPATCHER**: Authorized for all valid transitions in the parcel lifecycle.
- **SERVICE**: REST status updates are forbidden. S2's authenticated gRPC methods enforce `pod:update` and allow only the delivery-completion path to transition to `DELIVERED` (idempotent repeat supported).
- **COURIER**: Forbidden from calling S2 `PATCH /api/v1/parcels/{parcelId}/status` directly (403 Forbidden). Couriers submit Proof of Delivery to S3 (`POST /api/v1/courier/deliveries/{parcelId}/proof`), which validates assignment isolation and calls S2 using a confidential `SERVICE` token.

## Endpoint

`PATCH /api/v1/parcels/{parcelId}/status`

| Item | Value |
|---|---|
| Roles | `DISPATCHER` (`@PreAuthorize("hasRole('DISPATCHER')")`) |
| Forbidden | `CUSTOMER`, `MERCHANT`, `COURIER` (403) |
| Auth | `Authorization: Bearer <Keycloak access token>` |
| Content type | `application/json` |
| Actor | The token `sub` and role. The body cannot set the actor. A token with both roles is recorded as `DISPATCHER`. |

Dispatchers call it through the gateway (`PATCH /api/v1/parcels/{parcelId}/status` allows `ROLE_DISPATCHER`). Internal service status changes use S2's gRPC listener on port 9082 with a signed `SERVICE` token and method-specific POD permission. The REST endpoint rejects service tokens.

### Request

```json
{ "status": "FAILED", "reason": "Recipient not available", "note": "Gate locked, called twice" }
```

| Field | Rule |
|---|---|
| `status` | Required. One of the six statuses. |
| `reason` | Optional, at most 200 characters. Required when `status` is `FAILED`. Trimmed. |
| `note` | Optional, at most 500 characters. Trimmed. Stored in history only; never published or logged. |

### Response `200`

```json
{
  "parcelId": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
  "trackingRef": "ZT-ABCDEFGH12",
  "previousStatus": "PICKED_UP",
  "status": "FAILED",
  "changed": true,
  "changedAt": "2026-11-03T10:15:30.123456Z"
}
```

When `changed` is `false`, `previousStatus` equals `status` and `changedAt` is `null`.

## Errors

All errors use `application/problem+json` with a `code` property. Messages contain only ids and status names.

| Status | `code` | When |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | Body is not valid JSON, or `status` is not a known value |
| 400 | `INVALID_PARCEL_ID` | `{parcelId}` is not a UUID |
| 401 | (security) | Missing or invalid token |
| 403 | (security) | Token without `DISPATCHER` or `SERVICE` |
| 404 | `PARCEL_NOT_FOUND` | No parcel with that id |
| 409 | `INVALID_TRANSITION` | Transition not in the table; body adds `currentStatus`, `requestedStatus`, `allowedNext` |
| 409 | `PARCEL_TERMINAL` | Parcel is `DELIVERED` or `FAILED`; same extra fields, `allowedNext` is empty |
| 409 | `CONCURRENT_UPDATE` | Safety net if the row changed during the update; retry after reading again |
| 422 | `VALIDATION_FAILED` | `fieldErrors` keyed by field: missing `status`, `reason` or `note` too long, `FAILED` without `reason` |
| 503 | `STATUS_UPDATE_UNAVAILABLE` | The database write failed; nothing was changed |

Example `409`:

```json
{
  "type": "about:blank",
  "title": "Invalid status transition",
  "status": 409,
  "detail": "A parcel cannot move from PICKED_UP to DELIVERED.",
  "instance": "/api/v1/parcels/3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c/status",
  "code": "INVALID_TRANSITION",
  "currentStatus": "PICKED_UP",
  "requestedStatus": "DELIVERED",
  "allowedNext": ["IN_TRANSIT", "FAILED"]
}
```

## Consistency

One transaction per accepted change:

1. `SELECT ... FOR UPDATE` locks the parcel row. Concurrent requests for the same parcel run one after another.
2. Check the transition.
3. Update `status`, `updated_at` and `version + 1`. The update only applies if the version is unchanged.
4. Insert one `parcel.parcel_status_history` row.
5. Insert one `parcel.outbox_event` row.

If any step fails, the whole change is rolled back. Two identical concurrent requests record exactly one change; the second returns `changed: false`.

## History

Migration `V4__create_parcel_status_history.sql` creates `parcel.parcel_status_history`, which has these columns:
- `parcel_id`
- `from_status`, `to_status`
- `reason`, `note`
- `actor_subject`, `actor_role`
- `occurred_at`

A trigger rejects every `UPDATE` and `DELETE`, so history is append-only.
Index: `(parcel_id, occurred_at)`.

Migration `V3__add_parcel_version.sql` adds `parcel.parcel.version`, which starts at 0.

## Status event (outbox)

Migration `V5__create_parcel_outbox_event.sql` creates `parcel.outbox_event`:
- `event_id`, `aggregate_id`, `event_type`
- `payload` (JSONB)
- `occurred_at`, `published_at`, `attempts`
- a partial index on unpublished rows

This task only writes the rows. Publishing them to Kafka (topic, key, headers, ordering, retries, duplicates) is described in [parcel-events.md](parcel-events.md) (#58).

Each accepted change writes one row with `event_type = parcel.status.updated`. The payload column holds the full envelope from `contracts/events/event-envelope.schema.json`:

```json
{
  "eventId": "6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10",
  "eventType": "parcel.status.updated",
  "occurredAt": "2026-11-03T10:15:30.123456Z",
  "aggregateId": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
  "payload": {
    "trackingRef": "ZT-ABCDEFGH12",
    "previousStatus": "PICKED_UP",
    "status": "FAILED",
    "actorRole": "SERVICE",
    "occurredAt": "2026-11-03T10:15:30.123456Z",
    "failureReason": "Recipient not available"
  }
}
```

`failureReason` is present only when `status` is `FAILED`.
The payload never contains these fields:
- names, phone numbers or addresses
- the note
- the actor subject

**Schema.** The payload is defined in `contracts/events/parcel-status-updated.schema.json` (#58) and checked by the producer contract test. See [parcel-events.md](parcel-events.md) for the publishing rules.

## S3 POD integration

S3 verifies the courier owns an accepted assignment, records the proof, and submits completion to S2's `CompleteParcelDelivery` gRPC method on port 9082. It uses a cached Keycloak `SERVICE` JWT scoped with `pod:update`; S2 validates signature, issuer, audience, role and RPC permission. S2 makes the idempotent `DELIVERED` transition and writes the Kafka outbox event transactionally. A courier or generic service token cannot invoke the REST status endpoint to bypass S3.
