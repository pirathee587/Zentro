# Kafka parcel status workflow

## Ownership and architecture

Courier app -> gateway -> `PATCH /api/v1/courier/deliveries/{parcelId}/status` (S3) -> SERVICE-authenticated `UpdateCourierParcelStatus` gRPC (S2) -> S2 PostgreSQL status + history + outbox -> Kafka -> three independent consumers:

| Responsibility | Existing implementation | Group |
|---|---|---|
| Publication | S2 `ParcelStatusService`, `OutboxRepository`, `OutboxPublisher` | producer, parcel UUID key |
| Customer notification | S5 `ParcelEventConsumer`, `NotificationService`, `NotificationDispatcher` | `notification-integration-service` (`KAFKA_CONSUMER_GROUP`) |
| Merchant webhook | S5 `MerchantWebhookConsumer`, `WebhookDeliveryService` | `merchant-webhooks` (`WEBHOOK_CONSUMER_GROUP`) |
| Tracking | S4 `TrackingKafkaConsumer`, `TrackingService`, `TrackingStreamService` | `tracking-reporting-service` (`KAFKA_CONSUMER_GROUP`) |

S1 owns identities and courier profiles; S3 owns assignments; S2 owns status. S4 is a projection/read service. No application reads another service's database. Test-only SQL assertions use separate disposable databases and logins.

S3 requires the authenticated courier's assignment to be accepted. S2 locks the parcel and checks its existing lifecycle: `CREATED -> PICKED_UP -> IN_TRANSIT -> OUT_FOR_DELIVERY -> DELIVERED`, with `FAILED` allowed from `PICKED_UP`, `IN_TRANSIT`, or `OUT_FOR_DELIVERY` and requiring a reason. Terminal parcels cannot move again; a same-status request is an idempotent no-op. Existing POD upload/review/completion routes remain available; no POD activation or new delivery command is introduced. The existing customer tracking view now refreshes through its existing SSE hook.

## Existing v1 event contract

Topic: `parcel.status.updated` (not a new parallel status topic). The producer uses the typed `ParcelStatusEvent` record, serialized by the existing Jackson ObjectMapper and Kafka StringSerializer:

```json
{
  "eventId": "11111111-1111-4111-8111-111111111111",
  "eventType": "parcel.status.updated",
  "occurredAt": "2026-11-21T10:30:00Z",
  "aggregateId": "22222222-2222-4222-8222-222222222222",
  "payload": {
    "trackingRef": "ZT-7K3QX9M2PA",
    "previousStatus": "OUT_FOR_DELIVERY",
    "status": "DELIVERED",
    "actorRole": "COURIER",
    "occurredAt": "2026-11-21T10:30:00Z"
  }
}
```

`failureReason` is present only for FAILED; `merchantId` only for merchant parcels. Version is the existing Kafka `schemaVersion: 1` header, not a new JSON field (strict existing consumers reject extra envelope fields). Other headers include `eventId`, `eventType`, sanitized correlation ID and W3C trace context. No contact details, full addresses, notes, actor subjects, access tokens, or credentials are added to the event.

## Reliability and recovery

- S2 commits status/history/outbox atomically. Its scheduled relay reads committed rows with transaction-held `FOR UPDATE SKIP LOCKED`, waits for Kafka acknowledgement, and only then marks publication. Older unpublished parcel events block later ones. Broker failures retain the original event ID and retry with exponential backoff. Exhausted events remain in the outbox for controlled replay.
- All consumers deduplicate by event ID in their own database transaction before acknowledging. S5 notifications and webhooks have separate groups and processed-event namespaces; neither competes with S4.
- S5 persists a pending attempt before provider delivery. Mock SMS and signed webhooks run outside the courier request; retry schedulers operate on durable rows. Webhooks validate destinations against SSRF, sign HMAC-SHA256, retain attempt history, and expose dispatcher-authorized replay of FAILED deliveries. A timeout after remote acceptance can cause a repeated HTTP attempt with the same event ID: receivers must deduplicate it.
- S5's current provider is `MOCK_SMS`; its destination is a validated tracking reference. No real SMS/email provider or phone lookup is implemented or claimed. IN_TRANSIT is deliberately SKIPPED by the existing message policy; all other supported status events still flow through tracking/webhooks.
- S4 persists deduplicated history and derives current state from persisted chronological facts, preserving terminal outcomes when delayed events arrive. SSE notification occurs after transaction commit. Customer stream ownership is resolved and revalidated through S2's existing SERVICE-authenticated customer lookup, so a missing Kafka contact projection does not deny a legitimate new booking. Lookup failure closes the stream; the existing frontend reconnects and fetches current authoritative data.
- Existing rejected-record audit/retry handling remains: malformed S5 records are recorded and acknowledged; persistence failures retry without offset loss. S4 discards invalid contracts with safe categories and retries transient database failures. No new DLT or duplicate transport is introduced.

## Configuration (never enabled on shared resources by this change)

Defaults remain off for brokerless/shared development. Relevant switches are:

| Service | Settings |
|---|---|
| S2 | `KAFKA_BOOTSTRAP_SERVERS`, `OUTBOX_PUBLISHER_ENABLED=true`, `OUTBOX_*` bounded retry settings |
| S4 | `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_ENABLED=true`, group ID, SERVICE token + S2 gRPC configuration |
| S5 | `KAFKA_BOOTSTRAP_SERVERS`, `NOTIFICATIONS_CONSUMER_ENABLED=true`, `WEBHOOKS_ENABLED=true`, private `WEBHOOK_SECRET_KEY`, independent groups/retries |

`infra/compose/kafka.local.yml` is an optional development broker definition, with a persistent volume, internal `kafka:9092`, host `localhost:19092`, readiness checks and idempotent three-partition topic provisioning. It starts no application or worker. It is plaintext/local-only, not production infrastructure. Do not combine broker activation with `.env` files pointing at shared databases. The automated demo below does not use Compose or those files.

## Isolated Jaffna -> Kandy scenario

Build jars first, then run the opt-in class (PowerShell: retain quotes around dotted Maven property names):

```powershell
mvn -T1 -f backend/pom.xml -pl merchant-order-webhook-e2e -am -DskipTests package
mvn -T1 -f backend/pom.xml -pl merchant-order-webhook-e2e -am -DrunE2E "-Dzentro.e2e.kafkaStatus=true" "-Dzentro.e2e.python=python" "-Dit.test=KafkaParcelStatusWorkflowIT" "-Dfailsafe.failIfNoSpecifiedTests=false" "-Dtest=ParcelStatusServiceTest,TrackingStreamServiceTest,PublicTrackingControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" verify
```

The fixture inherits the existing disposable gateway stack. It owns PostgreSQL (separate databases/logins), Kafka and Keycloak Testcontainers plus six child JVMs. This opt-in fixture selects free loopback HTTP and gRPC port blocks; existing fixtures retain their original 18080-18085 / 9081-9083 defaults. It refuses conflicts rather than stopping unrelated processes. On Windows, set `zentro.e2e.python` to the installed Python executable (the `python3` Store alias may not be an interpreter). It imports only the committed test realm and generates temporary service credentials. It never deploys production or migrates shared Supabase.

Three scenario tests cover A-G collectively:

1. Customer Jaffna -> Kandy booking, dispatcher assignment, courier acceptance, other courier rejected with no history/outbox write, valid statuses and same-state retries, independent notification/tracking processing, authenticated DELIVERED SSE, duplicate publication and no merchant delivery.
2. Broker paused while FAILED commits, retained unpublished outbox, broker unpaused and eventual notification/tracking processing.
3. Merchant parcel delivered while its endpoint returns 503, signed attempts recorded, retry exhaustion remains recoverable, dispatcher replay succeeds after endpoint recovery, duplicate event does not create another delivery.

The receiver permits loopback HTTP only under the existing fixture-only setting. Production URL validation remains HTTPS/SSRF-protected. Recipient/contact values are synthetic. Raw fixture logs/reports stay under ignored `target/` and `.tmp/` paths; do not publish temporary tokens or signing keys.

## Changed files

| File | Service / class or method | Purpose |
|---|---|---|
| `backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/parcel/entity/ParcelStatusEvent.java` | S2, event/payload records | Strongly typed existing v1 envelope; omit optional null fields |
| `backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/parcel/service/ParcelStatusService.java` | S2, `eventJson` | Serialize typed event without changing atomic writes or wire contract |
| `backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/parcel/service/ParcelStatusServiceTest.java` | S2 tests | Five supported courier statuses produce exactly one private-data-free typed event |
| `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/graphql/service/CustomerBookingClient.java` | S4, `referenceForCustomer` | Reuse authorized S2 customer lookup for stream ownership |
| `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/tracking/service/TrackingStreamService.java` | S4, `subscribe` / `notifySubscribers` | Subscribe before a contact projection exists; reauthorize and fail closed |
| `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/tracking/controller/PublicTrackingController.java` | S4, SSE endpoints | Route customer streams through authoritative ownership lookup |
| `backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/tracking/service/TrackingStreamServiceTest.java` | S4 tests | Missing-projection customer subscription and revocation/denial coverage |
| `frontend/zentro-web/src/portals/customer/components/TrackView.tsx` | Customer `TrackView` | Wire existing SSE hook to an authoritative refresh; retain polling fallback |
| `frontend/zentro-web/src/shared/domain/tracking/useTrackingStream.ts` | Shared hook | Treat empty reference as disabled for public/no-reference views |
| `frontend/zentro-web/src/portals/customer/pages/__tests__/TrackParcelPage.test.tsx` | Customer UI tests | Immediate delivered refresh and public stream privacy |
| `backend/merchant-order-webhook-e2e/src/test/java/com/zentro/e2e/DisposableGatewayStack.java` | Shared test fixture port helpers | Free port blocks for the opt-in scenario, unchanged defaults for existing suites |
| `backend/merchant-order-webhook-e2e/pom.xml` | Test-only module | Managed Kafka client dependency for duplicate-event injection |
| `backend/merchant-order-webhook-e2e/src/test/java/com/zentro/e2e/KafkaParcelStatusWorkflowIT.java` | Cross-service fixture | Real authenticated API -> DB/outbox -> Kafka -> S5 + S4 scenario tests |
| `infra/compose/kafka.local.yml` | Local development infrastructure | Optional KRaft broker, health, persistence, topics; no service activation |
| `README.md` | Project overview | Correct status ownership/event-flow description |
| `docs/events/parcel-status-workflow.md` | Workflow report | Architecture, contract, settings, recovery, test commands and limitations |

## Verification results

Verified on 2026-11-22 using fresh packaged service jars:

| Check | Actual outcome |
|---|---|
| Clean reactor package (all services + fixture, tests skipped) | PASS; compilation/package only |
| `ParcelStatusServiceTest` | 24 passed, 0 failures/errors/skips |
| `TrackingStreamServiceTest` | 16 passed, 0 failures/errors/skips |
| `PublicTrackingControllerTest` | 7 passed, 0 failures/errors/skips |
| `KafkaParcelStatusWorkflowIT` | 3 passed, 0 failures/errors/skips; real PostgreSQL/Kafka/Keycloak and separate service JVMs, 228.2 seconds |
| Customer tracking Vitest suite | 18 passed, 0 failed/skipped |
| Frontend ESLint / TypeScript / production build | PASS; 9 existing lint warnings and existing large-bundle warning |
| Optional Kafka Compose syntax | `docker compose -f infra/compose/kafka.local.yml config --quiet`: PASS; never started |
| `git diff --check` | PASS |

The scenario log reports BUILD SUCCESS at 19:51:33 Asia/Colombo. Its three named cases are `customerDeliveryStreamsAndDeduplicatesWithoutMerchant`, `committedFailureSurvivesKafkaOutage`, and `merchantWebhookFailureDoesNotRollBackDeliveryAndCanRecover`. Evidence is local in `.tmp/kafka-status-scenario.log`, `backend/merchant-order-webhook-e2e/target/failsafe-reports/`, and `target/e2e-*.log`. These include real persisted outbox acknowledgements, independent S4/S5 consumer outcomes, a received DELIVERED SSE frame, HMAC verification, failed/replayed webhook history, and duplicate-event checks. Testcontainers and child JVMs are cleaned up by the fixture.

Focused S2 JaCoCo report after the clean build and final 24-test execution: `ParcelStatusService` 67/80 lines (83.75%) and 46/60 branches (76.67%). This is class-level focused-test coverage, not end-to-end or whole-project coverage. S4 has no configured coverage report; no percentage is claimed.

An earlier broad reactor run executed 1,507 tests before stopping in S2: 1,506 passed and one pre-existing `BookingPersistenceIntegrationTest.oneSchemaValidCreatedEvent` assertion failed because its forbidden substring `1438` appeared inside a generated timestamp. That test was left unchanged. The focused results above do not claim the full reactor is green. Initial scenario setup failures (occupied port, Windows python3 alias, redundant create-only client provisioning, stale generated V7 migration) were diagnosed and resolved in test configuration/build output; no shared database repair or migration was performed.

Mock SMS is not evidence of real customer receipt. Real SMS/email contact resolution is still absent from the existing mock contract. The SSE API and UI component tests verify the refresh contract; a manual browser/device demo was not run. IN_TRANSIT notification suppression remains the existing explicit policy. Public tracking uses its existing polling path; authenticated customer tracking uses SSE plus polling fallback. No production deployment, shared Supabase migration, live worker activation, git commit, push, or merge was performed.
