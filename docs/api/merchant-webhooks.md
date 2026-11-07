# Merchant webhooks (ZD-17)

Service: S5 Notification & Integration (`notification-integration-service`, port 8085). Issue: #32.
Events: S2 parcel events ([parcel-events.md](parcel-events.md), #58). Merchant orders: [merchant-orders.md](merchant-orders.md) (#23).

A merchant registers one HTTPS endpoint. S5 then POSTs every event of that merchant's parcels to it, signed with HMAC-SHA256, and records each delivery attempt with its response code. Failed deliveries are retried with back-off.

## Flow

1. A merchant creates an order (`POST /api/v1/merchant/orders`). S2 stores the parcel and a `parcel.created` event with `source: MERCHANT_ORDER` and `merchantId` in one transaction. Every later status change emits `parcel.status.updated` with the same `merchantId`.
2. S2 publishes the events to Kafka (outbox, at-least-once, ordered per parcel).
3. S5's webhook consumer (group `merchant-webhooks`, separate from notifications) de-duplicates on `eventId`, skips events without `merchantId` (customer bookings) or without an active endpoint, and stores a `PENDING` delivery.
4. S5 checks the endpoint address again, signs the event and POSTs it. The outcome and response code are stored. Retries run in a scheduler, never in the Kafka listener.

`merchantId` is the merchant's Keycloak subject (the token `sub`). It is the same value S2 stores as `parcel.merchant_id` and S5 reads from the merchant's token when the endpoint is registered, so no extra lookup is needed.

## Registration API

Base path `/api/v1/merchant/webhooks`, through the gateway (route to S5, `ROLE_MERCHANT`). S5 checks the token and role again.

| Method and path | What it does | Success |
|---|---|---|
| `POST /api/v1/merchant/webhooks` | Register the endpoint, or replace URL and secret and enable it again | `201` (new) / `200` (replaced), body includes the new `secret` |
| `GET /api/v1/merchant/webhooks` | Current configuration, never the secret | `200` |
| `DELETE /api/v1/merchant/webhooks` | Disable the endpoint; nothing more is sent | `204` |
| `POST /api/v1/merchant/webhooks/secret` | Rotate the signing secret of the active endpoint | `200`, body includes the new `secret` |
| `GET /api/v1/merchant/webhooks/deliveries?page=0&size=20` | Own delivery attempts, newest first (`size` 1-100) | `200` |

Rules:
- **Identity:** the merchant is always the token subject. There is no merchant id in the path, so a merchant can only see or change its own endpoint and deliveries. Without a registered endpoint the API returns `404`.
- **Approval:** registering and rotating also call S1 `GET /api/v1/users/me` with the caller's token. The merchant must be `APPROVED` and the account `ACTIVE`, the same rule S2 applies to merchant orders.
- **One endpoint per merchant.** Registering again replaces it. Open deliveries then go to the new URL with the new secret.

### Register

```http
POST /api/v1/merchant/webhooks
Authorization: Bearer <merchant access token>
Content-Type: application/json

{ "url": "https://shop.example.com/swiftdrop/webhooks" }
```

`201 Created`, `Location: /api/v1/merchant/webhooks`, `Cache-Control: no-store`:

```json
{
  "id": "0b7c2f6e-5d1a-4c3b-9e8f-1a2b3c4d5e6f",
  "url": "https://shop.example.com/swiftdrop/webhooks",
  "active": true,
  "secretPrefix": "whsec_Ab",
  "createdAt": "2026-11-06T08:00:00.123456Z",
  "updatedAt": "2026-11-06T08:00:00.123456Z",
  "secret": "whsec_AbCdEf...43 characters..."
}
```

`GET` returns the same fields without `secret`.

### Deliveries

```json
{
  "items": [
    {
      "id": "6a1d...",
      "eventId": "6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10",
      "eventType": "parcel.status.updated",
      "parcelId": "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c",
      "status": "RETRY",
      "attempts": 2,
      "responseStatus": 503,
      "lastError": "HTTP 503",
      "nextAttemptAt": "2026-11-06T08:01:10Z",
      "createdAt": "2026-11-06T08:00:30Z",
      "updatedAt": "2026-11-06T08:00:50Z"
    }
  ],
  "page": 0,
  "size": 20,
  "total": 1
}
```

`status` is `PENDING`, `SUCCEEDED`, `RETRY` or `FAILED`. The payload and the response body are never returned or stored.

### Errors

All errors are `application/problem+json` with a `code` property. Messages name the rule only.

| Status | `code` | When |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | Body is not valid JSON, or `page`/`size` is not a number |
| 401 | (security) | Missing or invalid token |
| 403 | (security) | Token without `MERCHANT` |
| 403 | `MERCHANT_NOT_APPROVED` | Merchant application not approved or account not active (register, rotate) |
| 404 | `WEBHOOK_NOT_FOUND` | No endpoint registered (GET, DELETE) or no active endpoint (rotate) |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Body is not `application/json` |
| 422 | `VALIDATION_FAILED` | `fieldErrors.url` explains the broken URL rule (see "Endpoint rules") |
| 503 | `IDENTITY_UNAVAILABLE` | S1 could not be reached; nothing was stored |
| 503 | `WEBHOOKS_UNAVAILABLE` | No encryption key configured on the server, or the database is unavailable |

## Signing secret

- **Generation:** 32 random bytes, returned as `whsec_` + base64url (no padding), 49 characters in total. The server creates it; the merchant never chooses it.
- **Shown once:** only in the `POST` (register) and `POST /secret` (rotate) responses, sent with `Cache-Control: no-store`. `GET` shows only `secretPrefix` (the first 8 characters).
- **Storage:** encrypted with AES-256-GCM using `WEBHOOK_SECRET_KEY`, bound to the merchant id. It is encrypted rather than hashed because S5 needs the plain value to sign. It is never logged.
- **Rotation:** `POST /api/v1/merchant/webhooks/secret` replaces the secret immediately. Deliveries sent after that, including retries of older events, use the new secret. Update the receiver first and accept both secrets for a short time if needed.
- **Lost secret:** rotate it. It cannot be shown again.

## Delivery request

```http
POST <registered URL>
Content-Type: application/json
User-Agent: SwiftDrop-Webhooks/1.0
X-SwiftDrop-Event-Id: 6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10
X-SwiftDrop-Event-Type: parcel.status.updated
X-SwiftDrop-Timestamp: 1762416000
X-SwiftDrop-Signature: sha256=5d2c...64 hex characters

{"eventId":"6b0c1f8e-...","eventType":"parcel.status.updated","occurredAt":"...","aggregateId":"3f1c2b7e-...","payload":{"trackingRef":"ZT-ABCDEFGH12","previousStatus":"PICKED_UP","status":"IN_TRANSIT","actorRole":"DISPATCHER","occurredAt":"...","merchantId":"..."}}
```

- **Body:** the event envelope exactly as published by S2 (`contracts/events/parcel-created.schema.json`, `parcel-status-updated.schema.json`). It contains no names, phone numbers or addresses.
- **Headers:** `X-SwiftDrop-Timestamp` is unix seconds at the time of this attempt. `X-SwiftDrop-Signature` is `sha256=` + lowercase hex of HMAC-SHA256 over `"<timestamp>.<raw body>"`, keyed with the full secret string (`whsec_...`, UTF-8).
- **Answer:** any `2xx` within 5 seconds. The response body is ignored.

### Verifying the signature (receivers)

The signing contract (#127, ZD-052) is unchanged since ZD-17. S5 signs every attempt, including retries, with a fresh timestamp. The reference implementation is `WebhookSignatureVerifier` (`notification-integration-service`, package `webhook.security`); S5's tests use it to prove every delivery and retry verifies.

**Signed content.** The signed bytes are `<X-SwiftDrop-Timestamp>` + `.` + the raw request body, as UTF-8 bytes. The key is the full secret string including `whsec_` (UTF-8). The body is the event JSON exactly as sent: never re-serialise or pretty-print it before verifying. `Content-Type` is `application/json` without a `charset` parameter; the body is always UTF-8.

**Header formats.**

| Header | Format |
|---|---|
| `X-SwiftDrop-Timestamp` | Unix seconds, digits only (for example `1762416000`) |
| `X-SwiftDrop-Signature` | `sha256=` followed by exactly 64 lowercase hex characters, one signature per request |
| `X-SwiftDrop-Event-Id` | The event id (UUID); the same value as `eventId` in the body |

**Steps.**
1. Read the raw body bytes before parsing JSON.
2. Reject the request if either header is missing, or if their format is not exactly as above.
3. Compute `HMAC-SHA256(secret, timestamp + "." + body)`, write it as `sha256=` + lowercase hex, and compare it with the header using a **constant-time** comparison (for example `MessageDigest.isEqual`, `hmac.compare_digest`, `crypto.timingSafeEqual`). Never use `String.equals` or `==`.
4. Reject the request if the timestamp is more than **300 seconds** older than your clock (replay window) or more than **60 seconds** in the future (clock skew). The timestamp is part of the signed content, so it cannot be changed without breaking the signature.
5. Only after the signature is valid, de-duplicate on `X-SwiftDrop-Event-Id`. The same event can arrive more than once: retries and redeliveries have a new timestamp and signature but the same event id. Keep the ids you have processed for at least the replay window, and answer a duplicate with `2xx` without processing it again. Recording ids only after verification means a forged request cannot block a real event.
6. Answer `2xx` within 5 seconds and do the slow work afterwards.

**Failure meanings** (the `WebhookSignatureVerifier.Result` values):

| Result | Meaning | Suggested response |
|---|---|---|
| `VALID` | Genuine and recent | Process, then `2xx` |
| `MISSING_HEADER` | Timestamp or signature header absent or blank | `400`; not from S5 or stripped by a proxy |
| `MALFORMED_TIMESTAMP` | Timestamp is not whole unix seconds | `400` |
| `MALFORMED_SIGNATURE` | Not `sha256=` + 64 lowercase hex characters | `400` |
| `SIGNATURE_MISMATCH` | Body or timestamp changed, wrong secret (for example after a rotation), or forged | `401`; check you use the current secret and the raw body |
| `TIMESTAMP_TOO_OLD` | Genuine but older than 300 s: a replay, or a very slow queue on your side | `401`; do not process |
| `TIMESTAMP_IN_FUTURE` | More than 60 s ahead: your clock is wrong, or the request was tampered with | `401`; check NTP on your server |

Answering `4xx` (other than 408/429) makes S5 mark the delivery `FAILED`. A temporary problem on your side should answer `5xx` so S5 retries.

**Java example** (same logic as `WebhookSignatureVerifier`, standard library only):

```java
static boolean verify(String secret, String timestamp, String signature, byte[] rawBody, long nowSeconds)
    throws Exception {
  if (timestamp == null || signature == null
      || !timestamp.matches("[0-9]{1,12}") || !signature.matches("sha256=[0-9a-f]{64}")) {
    return false;
  }
  Mac mac = Mac.getInstance("HmacSHA256");
  mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
  mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
  String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
  if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
      signature.getBytes(StandardCharsets.US_ASCII))) {
    return false;                                   // SIGNATURE_MISMATCH
  }
  long age = nowSeconds - Long.parseLong(timestamp);
  return age <= 300 && age >= -60;                  // TIMESTAMP_TOO_OLD / TIMESTAMP_IN_FUTURE otherwise
}
```

**With openssl** (body saved to `body.json` exactly as received, no extra newline):

```bash
SECRET='whsec_...'
TS='1762416000'                     # X-SwiftDrop-Timestamp
{ printf '%s.' "$TS"; cat body.json; } | openssl dgst -sha256 -hmac "$SECRET" | awk '{print "sha256=" $NF}'
# must equal the X-SwiftDrop-Signature header; then check that TS is within 300 s of `date +%s`
```

**Secret rotation.** After `POST /api/v1/merchant/webhooks/secret`, the next attempt is signed only with the new secret. There is a single signature per request, so update the receiver right away (or accept either secret for a short time).

## Retries and timeouts

| Result of an attempt | Outcome |
|---|---|
| `2xx` | `SUCCEEDED` |
| `408`, `429`, `5xx`, timeout, connection or DNS error | `RETRY` after `10 s * 2^(attempt - 1)` (10 s, 20 s, 40 s ... capped at 30 min) |
| Any other status: other `4xx`, `3xx` (redirects are not followed), `1xx` | `FAILED` at once (permanent) |
| Endpoint now resolves to a blocked address | `FAILED` at once (`Endpoint not allowed: ...`) |
| Retryable result on attempt `WEBHOOK_MAX_ATTEMPTS` (default 8) | `FAILED` (`... (gave up after 8 attempts)`) |
| Endpoint disabled before the attempt | Closed as `FAILED` with `Subscription disabled`; not counted as a failure |

- Connect timeout 2 s, response timeout 5 s, no redirects.
- The response code (if any) and a short error are stored for every attempt. The response body is never stored.
- **Order:** a later event for a parcel is held back while an earlier event for the same parcel is still `PENDING` or `RETRY`. Different parcels do not wait for each other.
- **Duplicates:** an event is delivered once per endpoint (`processed_event` for consumer `merchant-webhooks`, and a unique key on subscription and event). Receivers must still de-duplicate, because a crash after sending but before recording can repeat an attempt.
- **Several instances:** deliveries are locked with `FOR UPDATE SKIP LOCKED`, so two instances never send the same delivery at the same time.

### Dead-letter (FAILED) handling

A delivery that fails permanently stays as a `FAILED` row in `notification.webhook_delivery`, with its attempts, last response code and error. It is logged at ERROR level and turns the `webhooks` health component DOWN for an hour. A Kafka `.DLT` topic is not used, because the failure happens on the outbound HTTP call, not while reading Kafka.

Retry a merchant's failed deliveries after the cause is fixed:

```sql
UPDATE notification.webhook_delivery
   SET status = 'RETRY', attempts = 0, next_attempt_at = now(), updated_at = now()
 WHERE status = 'FAILED' AND last_error IS DISTINCT FROM 'Subscription disabled'
   AND subscription_id = (SELECT id FROM notification.webhook_subscription WHERE merchant_id = '<merchant id>');
```

Inspect recent deliveries:

```sql
SELECT d.event_type, d.parcel_id, d.status, d.attempts, d.response_status, d.last_error, d.next_attempt_at
  FROM notification.webhook_delivery d
  JOIN notification.webhook_subscription s ON s.id = d.subscription_id
 WHERE s.merchant_id = '<merchant id>'
 ORDER BY d.created_at DESC LIMIT 20;
```

## Endpoint rules

Checked when the endpoint is registered and again before every attempt:

- `https` only, at most 2048 characters, absolute, with a host.
- No user name or password, no fragment.
- The host must resolve, and every address it resolves to must be public unicast. Blocked:
  - loopback: `127.0.0.0/8`, `::1`
  - private: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `fc00::/7`
  - link-local and cloud metadata: `169.254.0.0/16`, `fe80::/10`
  - carrier-grade NAT `100.64.0.0/10`, multicast, unspecified (`0.0.0.0`, `::`), documentation, benchmarking and reserved ranges
  - IPv6 forms that embed a blocked IPv4 address (IPv4-mapped, NAT64, 6to4)

**Development only:** `WEBHOOKS_ALLOW_INSECURE_LOCAL=true` also accepts `http://localhost`, `127.0.0.1` and `[::1]` (http or https), as long as the name resolves only to loopback. Every other private address stays blocked. It is `false` by default and must stay `false` outside local development.

**Known limit:** the HTTP client resolves the host again when it connects, so a host whose DNS answer changes between the check and the connection (DNS rebinding) is not fully covered. Checking before every attempt keeps that window small.

## Configuration

All settings come from environment variables. Names are listed in `backend/notification-integration-service/.env.example`.

| Variable | Default | Meaning |
|---|---|---|
| `WEBHOOKS_ENABLED` | `false` | Turns the webhook consumer and retry scheduler on. While off, S5 does not read events for webhooks; the registration API still works when a key is set. |
| `WEBHOOK_SECRET_KEY` | (none) | Base64 of 32 random bytes (`openssl rand -base64 32`). Required when enabled; start-up fails without it. Keep it in a secret store; never commit it. |
| `WEBHOOKS_ALLOW_INSECURE_LOCAL` | `false` | Development only, see "Endpoint rules" |
| `WEBHOOK_CONSUMER_GROUP` | `merchant-webhooks` | Kafka consumer group of the webhook listener |
| `WEBHOOK_MAX_ATTEMPTS` | `8` | Attempts before a delivery is `FAILED` |
| `WEBHOOK_RETRY_POLL_MS` | `5000` | Pause between retry rounds |
| `WEBHOOK_RETRY_BASE_DELAY_MS` | `10000` | Delay after the first failed attempt (doubles per attempt) |
| `WEBHOOK_RETRY_MAX_DELAY_MS` | `1800000` | Retry delay limit (30 min) |
| `WEBHOOK_CONNECT_TIMEOUT_MS` | `2000` | Connect timeout |
| `WEBHOOK_READ_TIMEOUT_MS` | `5000` | Response timeout |
| `WEBHOOK_HEALTH_FAILED_WINDOW_MINUTES` | `60` | A `FAILED` delivery in this window turns health DOWN |
| `WEBHOOK_HEALTH_STUCK_MINUTES` | `10` | An open delivery overdue by more than this turns health DOWN |
| `IDENTITY_BASE_URL` | `http://localhost:8081` | S1 Identity, for the merchant approval check |

`KAFKA_BOOTSTRAP_SERVERS` and the database settings are shared with notifications ([notifications.md](notifications.md)).

**Changing `WEBHOOK_SECRET_KEY`:** stored secrets can then no longer be decrypted, and their deliveries fail with `Signing secret unavailable`. After a key change every merchant must rotate its secret. Re-encrypting with two keys is not supported yet.

## Observability

| Signal | Name |
|---|---|
| Counter | `zentro.webhooks.succeeded`, `zentro.webhooks.failed`, `zentro.webhooks.retried` |
| Counter | `zentro.webhooks.skipped`: no `merchantId`, no active endpoint, or endpoint disabled |
| Counter | `zentro.webhooks.duplicates`, `zentro.webhooks.rejected` (malformed events) |
| Gauge | `zentro.webhooks.pending` (`PENDING` + `RETRY`), `zentro.webhooks.failed.total` |
| Health component | `webhooks` |
| Logs | Event id, merchant id, response code, short error and attempt count. Never the secret, the signature, the token or the payload. |

The health component shows `enabled`, `pending`, `failedRecently` and `oldestOverdueSeconds`. It is DOWN when a delivery failed permanently in the last hour, or when an open delivery is overdue by more than 10 minutes. It is not part of the Kubernetes liveness/readiness groups, so failing merchant endpoints never restart S5.

## Database

Migration `V2__webhook_schema.sql` (schema `notification`):

- `webhook_subscription`: `id`, `merchant_id` (unique), `url`, `secret_ciphertext`, `secret_prefix`, `active`, `created_at`, `updated_at`.
- `webhook_delivery`: `id`, `subscription_id`, `event_id`, `event_type`, `parcel_id`, `payload` (envelope as received), `payload_sha256`, `status`, `attempts`, `response_status`, `last_error`, `next_attempt_at`, `created_at`, `updated_at`; unique `(subscription_id, event_id)`.
- Indexes: `(status, next_attempt_at)`, `(subscription_id, created_at)` and `(subscription_id, parcel_id, created_at)`.
- Malformed events go to the existing `rejected_event` table with the reason prefixed `merchant-webhooks:`.

## Open items

- **Merchant portal Webhooks page** (`/merchant/webhooks` in the README): frontend work for the merchant UI owner. The API above is ready for it.
- **Schema confirmation:** the optional `merchantId` in both event schemas needs confirmation from the S4 and S5 owners (@hephzibah-peries, @Deadsecnote1).
- **Encryption key rotation** with two active keys is not supported; see "Configuration".
- **Deployment:** `WEBHOOK_SECRET_KEY` must be provided through the deployment's secret store (deployment owner).
