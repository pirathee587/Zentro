# Correlation and trace propagation (#143)

The gateway and five servlet services accept `X-Correlation-Id` only when there
is exactly one value matching `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`: 1–128 ASCII
characters, beginning with a letter or digit. Safe non-UUID IDs such as
`request-123` remain unchanged. Missing, empty, duplicate, oversized, non-ASCII,
whitespace, control-character and other unsafe values are replaced with a UUID.
Values are not trimmed. Correlation identifiers are public diagnostic labels;
never put personal information, passwords, tokens or other secrets in them.

The accepted ID is forwarded in the request and returned in `X-Correlation-Id`,
including application-level 401/403 responses. HTTP parser failures before the
application accepts a request cannot guarantee this header. Error response
bodies, authentication, authorization and ownership checks are unchanged.

## Runtime ownership

`zentro-security-starter` provides a separate servlet-only correlation
auto-configuration. Its filter runs before security, stores the accepted value
as a request attribute, normalizes downstream header reads, and scopes the
`correlationId` MDC key to each request/error/async dispatch. Spring MVC
`Callable` execution also receives a scoped value and a Micrometer context
snapshot captured after the server observation starts. Every scope restores the
previous MDC value even on failure. Arbitrary application-created executors and
the producer of a `DeferredResult` do not automatically inherit MDC; the async
response redispatch does. Never keep MDC populated for the lifetime of a stream.

The gateway retains its independent reactive WebFilter and early ordering,
including the existing tracking-stream response headers. Correlation lives in
the exchange and Reactor context, rather than a thread-local scope spanning an
asynchronous subscription. Existing gateway access logs include the accepted
ID explicitly. Reactor automatic context propagation supports tracing scopes.

Micrometer's OpenTelemetry bridge is resolved using each application's existing
Boot BOM: 3.3.5 for services, 3.5.16 for the gateway. Boot's existing HTTP
observations own server/client spans. No extra HTTP tracing filters, manual
HTTP spans, or Java agents are added. Kafka and gRPC use the narrowly
scoped adapters described below because their existing boundaries were not
instrumented.
W3C `traceparent`/`tracestate` propagation is enabled; baggage is disabled and
removed from outbound requests. Trace IDs and correlation IDs are separate.
Boot's default sampling applies (0.1 unless overridden through standard Boot
configuration). No exporter or collector endpoint is enabled by this change.

## Internal REST boundaries

The starter customizes the existing injected `RestClient.Builder`. The
`zentro.correlation.internal-base-urls` allowlist is empty by default and is
bound to the same configured service URLs as the existing clients:

| Caller | Approved destinations | Existing clients |
|---|---|---|
| S2 | S1 identity base URL | `integration/IdentityMerchantClient` |
| S3 | S1 identity and S2 parcel base URLs | `IdentityCourierClient`, `ParcelStatusClient` |
| S5 | S1 identity base URL | `webhook/client/IdentityMerchantClient` |

Matching includes scheme, hostname, effective port and base-path boundary.
The interceptor copies only the accepted correlation ID from the active scope;
Boot supplies W3C trace headers. For other destinations it removes correlation,
traceparent, tracestate and baggage. Existing authentication headers and bodies
remain the client's responsibility and are never copied from another request.
In particular, S3's Keycloak token client retains its own client-credentials
authentication but receives no propagated diagnostic headers. S5's separate JDK
merchant-webhook adapter is unchanged and receives no new headers.

Code that constructs an uncustomized `RestClient` directly bypasses this
integration. Production clients use injected builders; tests must exercise that
path. Do not add general header-copying or request/response body logging.

## Kafka

The existing starter's Kafka dependency is optional: S1 and the reactive
gateway do not acquire Kafka dependencies through it. A classpath-conditional
auto-configuration attaches a record interceptor to the default listener
factory and S5's separate `webhookListenerContainerFactory`. No consumer group,
acknowledgment mode, event JSON, partition key, event ID, deduplication rule,
retry count, backoff, or recovery destination is changed.

Kafka headers are case-sensitive: `X-Correlation-Id`, `traceparent`, and
`tracestate`. Each must have at most one value; duplicate or oversized values
are ignored. Correlation uses the HTTP policy. W3C context is validated and
canonicalized by OpenTelemetry, with a 256-byte traceparent input limit and a
512-byte tracestate limit. Invalid trace context starts a new root span. No
baggage, JWT, credential, or arbitrary header is copied.

Each listener attempt starts a fresh consumer span. Scope cleanup runs after
success, failure, and consumer-thread shutdown. Retry/recovery callbacks open
their own bounded scopes and log fixed categories with numeric partition and
offset. Existing bounded S4 and unlimited S5 retry policies remain intact.
Safe recovery logging replaces the framework's payload-bearing default log;
seek/backoff signals omit nested exception messages after classification and
seeking have already occurred. Container observation spans are disabled on
these factories to avoid duplicating the explicit attempt spans.

## S1 gRPC

The existing server registers a separate propagation interceptor outside the
unchanged security interceptor. Metadata keys are `x-correlation-id`,
`traceparent`, and `tracestate`; validation follows the same rules as Kafka.
Each RPC owns one server span, with a short scope around handler startup and
each listener callback. Completion, cancellation, and exceptions end the span
and restore the previous context. Rejected authentication is also scoped.
Unhandled exceptions still map to UNKNOWN, with sensitive nested messages kept
out of transport diagnostics; explicitly declared statuses are preserved.
No production gRPC client, protobuf, authentication, authority, or deadline
contract is changed.

## Durable handoffs and migrations

| Migration | Service-owned tables |
|---|---|
| S2 `V9__outbox_propagation_metadata.sql` | `parcel.outbox_event` |
| S3 `V3__proof_propagation_metadata.sql` | `dispatch.proof_of_delivery` |
| S5 `V3__delivery_propagation_metadata.sql` | `notification.notification_attempt`, `notification.webhook_delivery` |

Each migration adds nullable `correlation_id` (128), `traceparent` (256), and
`tracestate` (512) columns only. Run migrations before deploying new binaries.
Old binaries can continue writing null metadata during a rolling upgrade.
There is no backfill, cross-service database access, payload rewrite, new
index, or change to business keys.

Repositories capture validated current context in the same INSERT and
transaction as the existing work. Idempotent replays do not overwrite the
original metadata. Claim/read projections restore the stored parent for each
outbox publish, proof completion, notification delivery, or webhook attempt.
Every attempt gets a fresh span ID, including retries and work after restart;
the original parent is never installed as an active completed span. Scope
cleanup occurs on returns and exceptions. Exception contents are not recorded
as span events. Existing transactions, locks, ordering, leases, retry policies,
signature bytes, and commit/acknowledgment timing are retained.

The outbox adds diagnostic Kafka headers alongside `eventId`, `eventType`, and
`schemaVersion`; its JSON is unchanged. Notification and webhook metadata stays
in internal columns and is not added to external webhook headers, signatures,
payloads, API DTOs, or history responses.

Legacy rows with null metadata use the typed event ID (S2/S5) or proof ID (S3)
as a stable correlation identifier and start a new root trace per attempt.
The unavailable original trace cannot be reconstructed. Old database rows and
Kafka messages remain processable without metadata.

## Limits and ownership

Browser CORS policy is unchanged: browser-supplied trace headers require a
separately reviewed policy change. Arbitrary executors and DeferredResult
producers still require an explicit context snapshot. Protocol/parser errors
before application dispatch cannot guarantee an application span or header.
No collector/exporter is configured here. Operators must not enable HTTP wire,
SQL parameter, or framework TRACE logging on sensitive traffic.

Issue #222 retains broader error-body/status standardization. This work changes
diagnostic logging and propagation only; the four known pre-existing S1
OpenAPI inventory omissions are not repaired here. Phase 1 safe-logging files
and Phase 2A HTTP handling are retained. Git publishing and independent issue
acceptance remain the user's/reviewer's responsibility.
