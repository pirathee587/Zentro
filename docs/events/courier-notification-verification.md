# Courier notification event verification

**Scope:** code and contracts on `develop`, verified for issue #136. This document records current behavior; it does not define or imply a new contract. No courier notification event or recipient lookup is currently implemented.

## Event inventory

The envelope used by published S2 events has `eventId` (UUID), `eventType` (string), `occurredAt` (date-time), `aggregateId` (parcel UUID), and `payload` (object). S2 publishes to the topic named by `eventType`, with the parcel aggregate ID as the Kafka key. The outbox publisher adds `eventType` and `schemaVersion=1` headers.

| Event | Topic | Producer | Kafka key | Payload fields | Outbox |
|---|---|---|---|---|---|
| `parcel.created` | `parcel.created` | S2 `ParcelBookingService` / `MerchantOrderService` | Parcel UUID (`aggregateId`) | `trackingRef` (string), `status` = `CREATED`, `source` (`CUSTOMER_BOOKING` or `MERCHANT_ORDER`), `occurredAt` (date-time); optional `merchantId` (string) for merchant parcels | Yes; booking/order and event are stored atomically. Not emitted for idempotent replay. |
| `parcel.status.updated` | `parcel.status.updated` | S2 `ParcelStatusService` | Parcel UUID (`aggregateId`) | `trackingRef` (string), `previousStatus` and `status` (status enum), `actorRole` (string), `occurredAt` (date-time); `failureReason` (string) only for `FAILED`; optional `merchantId` (string) | Yes; status, history and event are stored atomically after an accepted change. No event for rejected or unchanged status. |
| Courier assignment | None published by S3 | S3 assignment flow persists through `AssignmentRepository.insert` | No event key | No event payload | No assignment outbox event is present in the inspected flow. |

`contracts/events/delivery-assigned.schema.json` currently references only the generic envelope and defines no event payload. It is not evidence of a published assignment event. The S3 assignment row is keyed by parcel ID and stores courier profile/user identifiers in S3's own database; S5 must not read that database.

## Existing S5 consumer behavior

S5 notification consumer subscribes to `parcel.created` and `parcel.status.updated`, using consumer group `notification-integration-service` by default. The valid current template behavior is:

| Event / status | Current outcome |
|---|---|
| `parcel.created` / `CREATED` | Notify through `MOCK_SMS` using customer-facing booking wording. |
| `parcel.status.updated` / `PICKED_UP` | Notify through `MOCK_SMS` using customer-facing wording. |
| `parcel.status.updated` / `IN_TRANSIT` | Skip and store `SKIPPED`; no message is sent. |
| `parcel.status.updated` / `OUT_FOR_DELIVERY` | Notify through `MOCK_SMS` using customer-facing wording. |
| `parcel.status.updated` / `DELIVERED` | Notify through `MOCK_SMS` using customer-facing wording. |
| `parcel.status.updated` / `FAILED` | Notify through `MOCK_SMS` using customer-facing wording and the failure reason. |
| Assignment / reassignment / unassignment | No event is consumed and no notification is generated. |

The current mock destination is identified by parcel tracking reference, not a resolved person's contact. There is no courier-specific recipient rule or courier contact resolution. Existing messages are customer wording and must not be interpreted as courier notices.

## Idempotency and delivery guarantee

S5 records `(consumer, eventId)` in `notification.processed_event`, whose primary key is `(consumer, event_id)`. In the same database transaction, it inserts the processing marker and either a `SKIPPED` outcome or a `PENDING` notification attempt. A duplicate event ID is ignored. A database failure prevents acknowledgement and is retried by the Kafka error handler; a malformed event is recorded in `notification.rejected_event` and acknowledged to avoid a poison-message loop.

The initial send occurs after the database transaction commits. Retryable sender failures remain in S5's database for the retry scheduler. **External sender exactly-once delivery is not guaranteed:** a process failure after an external sender accepts a message but before S5 persists the send result can cause a repeated send. The database uniqueness constraint protects stored outcomes, not the external side effect.

## Findings

| ID | Classification | Finding |
|---|---|---|
| CNV-1 | Verified gap | S3 assignment persistence publishes no Kafka assignment event; the checked-in assignment schema defines no payload fields. |
| CNV-2 | Verified gap | S5 has no assignment consumer, courier-specific template, or courier recipient resolution. |
| CNV-3 | Verified gap | `parcel.status.updated` has no courier identifier or contact; its current notifications use customer-facing templates. |
| CNV-4 | OK | S2 accepted status changes and corresponding outbox records commit atomically; rejected and unchanged changes do not publish. |
| CNV-5 | OK | S5 deduplicates by stable event ID with its own database primary key and atomically stores the outcome. External sender exactly-once is not guaranteed (see above). |

## Decisions required before implementation

1. **Assignment event and assignment lifecycle.** Approve an event contract and decide reassignment/unassignment semantics (including whether any old assignee is informed). Once approved, specify event identity and parcel ordering consistently with the contract owner. Do not infer fields from the current generic schema.
2. **Courier contact resolution.** Choose one approved approach:
   - **S1 internal API lookup (proposed):** S5 resolves the courier's contact through an authorized S1 API. This keeps personal data out of Kafka, at the cost of a runtime dependency on S1 and an API contract/availability path.
   - **Contact in event:** include an approved destination in the event. This avoids a lookup at consumption time but puts personal data in the Kafka topic and expands the event contract, retention and access exposure.

## Proposed follow-up issue split

Owners are **to be assigned**; GitHub issue and maintainer history could not be verified during this docs-only change.

| Follow-up | Owning service | Scope |
|---|---|---|
| Define assignment event contract and lifecycle | Contract owners / S3 and S5 | Approve schema and semantics for assignment, reassignment and unassignment before implementation. |
| Publish approved assignment event | S3 Dispatch & Courier | Persist the approved outbox event atomically with successful assignment state. |
| Provide approved courier contact lookup | S1 Identity & Partner | If API option is chosen, define an authorized internal API and least-privilege response for S5. |
| Consume assignment event and notify courier | S5 Notification & Integration | Consume only the approved contract, resolve recipient per decision, store outcome idempotently and deliver through an approved channel/template. |

## Reproduction commands

Run from the repository root. The S2 module tests cover parcel status/outbox behavior; S5 module tests cover consumer behavior, idempotency and duplicate handling. Integration tests using Testcontainers require a working Docker daemon.

```sh
cd backend
mvn --batch-mode -pl parcel-pricing-service -am test
mvn --batch-mode -pl notification-integration-service -am test
mvn --batch-mode test package
```

The verification run's actual command results, per-module test counts, skipped-test reasons, Docker status and environment output should be recorded with the PR. Docker-based tests must be marked **NOT run** if Docker cannot start.

### Verification run (2026-11-07)

The Docker CLI command `docker --version` failed because `snap-confine` refused to run without the snapd AppArmor service. The Maven Testcontainers runs nevertheless connected directly to `/var/run/docker.sock` and reported Docker server `29.8.0`; Docker-backed tests below did run. The unprivileged Maven attempts initially failed because the sandbox blocked Mockito agent attachment and local sockets; the reported successful runs used elevated execution.

| Module | Test command | Tests | Failures | Errors | Skipped |
|---|---|---:|---:|---:|---:|
| `zentro-security-starter` | Included by `-am` and full reactor | 7 | 0 | 0 | 0 |
| `api-gateway` | Full backend build | 183 | 0 | 0 | 1 |
| `identity-partner-service` | Full backend build | 260 | 0 | 0 | 0 |
| `parcel-pricing-service` | Targeted and full backend build | 558 | 0 | 0 | 0 |
| `dispatch-courier-service` | Full backend build | 144 | 0 | 0 | 0 |
| `tracking-reporting-service` | Full backend build | 39 | 0 | 0 | 0 |
| `notification-integration-service` | Targeted and full backend build | 261 | 0 | 0 | 0 |

The single skipped test is `GatewayKeycloakIntegrationTests.upstreamAndGatewayFailuresUseTheSameProblemJsonShape`; its checked-in skip reason is “Known shape mismatch: upstream 5xx sanitizer omits `instance`” (documented in `docs/testing/gateway-integration-tests.md#findings`). Testcontainers tests ran in S1, S2, S3, S4, S5 and the gateway integration suite. The full `mvn --batch-mode test package` reactor completed with `BUILD SUCCESS`.

Environment output: Java `openjdk version "21.0.12.1" 2026-08-18` (Ubuntu build); Maven `Apache Maven 3.8.7`; Docker CLI failed as described above while Testcontainers found Docker server 29.8.0; kernel `Linux 7.0.0-42-generic`.
