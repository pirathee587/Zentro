# ZD-068 / Issue #143 implementation and verification

Date: 2026-11-09. Branch: `feature/143-event-correlation-tracing`. Base HEAD: `f452def60424e891786404d910e8248e8a285601`.

## Outcome by phase

- **Phase 1 retained:** all four S4 safe-logging source/test files are byte-identical to this task's starting snapshot.
- **Phase 2A retained:** servlet correlation remains in the existing security starter; Gateway stays reactive and independent. Existing HTTP policy, internal destination allowlist, Boot-managed W3C tracing and HTTP tests are retained. No new module or Boot/Cloud version change.
- **Phase 2B:** classpath-conditional Kafka instrumentation covers S4/S5 default factories and S5's separate webhook factory. Validated diagnostic headers establish fresh consumer attempt spans with scoped cleanup. Safe error-handler logs preserve existing classification, backoff and seek/recovery behavior while omitting payload-bearing exception chains. S1 has a separate server propagation interceptor around authentication and callbacks; no production gRPC client or protobuf change. Unexpected gRPC runtime failures retain UNKNOWN status while transport diagnostics omit sensitive causes; declared statuses remain unchanged.
- **Phase 3:** S2 captures metadata atomically with outbox INSERTs, restores the stored parent in a fresh producer attempt, and adds only diagnostic Kafka headers. JSON, event IDs, existing headers, partition keys, transactions, ordering and idempotency remain unchanged. Legacy rows use event ID correlation and a new root trace.
- **Phase 4:** S3 proof-completion and S5 notification/webhook durable rows capture context at creation and restore it per attempt, including retries, lease recovery and restart. Each attempt gets a fresh span ID. Existing locks, leases, retry decisions, replay rules and signed external webhook bytes remain intact. Legacy rows remain processable.
- **Phase 5:** architecture and relevant event/API prose document the implemented boundaries and limitations. Narrow application-log redactions remove unvalidated partner identifiers, JWT claim values and exception messages; validation/authentication decisions are unchanged. All five OpenAPI artifacts remain byte-identical to this task's starting snapshot (their prior Phase 2A correlation documentation remains).

## Metadata and dependencies

Accept exactly one correlation value matching `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`; otherwise generate a UUID. Safe non-UUID values are retained. W3C context is canonicalized using OpenTelemetry; traceparent input is bounded to 256 bytes and tracestate to 512. Only correlation/traceparent/tracestate propagate. No baggage, credentials, tokens, raw payloads or exception messages are injected into telemetry. Explicit attempt spans prevent accidental inheritance from unrelated worker threads. Existing HTTP observation spans remain the HTTP owners; Kafka container observation is disabled on instrumented factories to avoid duplicates.

The security starter's Kafka dependency is optional and its Kafka auto-configuration is classpath-conditional. S1 does not acquire Kafka from it; Gateway does not depend on the starter. Services keep Boot 3.3.5 and Gateway keeps Boot 3.5.16 / Cloud 2025.0.3. There is no new module or service-to-service Java dependency.

## Database migrations

| File | Owned tables |
|---|---|
| `backend/parcel-pricing-service/src/main/resources/db/migration/V9__outbox_propagation_metadata.sql` | `parcel.outbox_event` |
| `backend/dispatch-courier-service/src/main/resources/db/migration/V3__proof_propagation_metadata.sql` | `dispatch.proof_of_delivery` |
| `backend/notification-integration-service/src/main/resources/db/migration/V3__delivery_propagation_metadata.sql` | `notification.notification_attempt`, `notification.webhook_delivery` |

Each adds nullable `correlation_id varchar(128)`, `traceparent varchar(256)` and `tracestate varchar(512)` only. No backfill, indexes, business-key changes or cross-service database access. Apply service-owned migrations before deploying new binaries. Old writers can continue inserting null metadata.

## Verification

All seven application/library modules passed **1,950 tests**, with zero failures, errors or skips. Their packaging/verification steps succeeded. After `confluentinc/cp-kafka:7.8.0` was subsequently downloaded successfully, the separate E2E run of `MerchantApprovalGapIT` passed **2 tests**, with zero failures, errors or skips and Maven **BUILD SUCCESS**. The latest successful E2E run finished at **2026-11-09 22:12:42 +05:30**. Combined verification across these runs is **1,952 passing tests**; this does not mean the original full reactor command succeeded.

Historical full reactor attempt: Docker was available, but the optional E2E module failed in fixture setup while fetching `confluentinc/cp-kafka:7.8.0` (`ContainerFetchException`, caused by image retrieval timeout). That original command returned **BUILD FAILURE** (exit 1), with one E2E setup error before business assertions.

| Module | Passed | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| zentro-security-starter | 42 | 0 | 0 | 0 |
| api-gateway | 208 | 0 | 0 | 0 |
| identity-partner-service (S1) | 346 | 0 | 0 | 0 |
| parcel-pricing-service (S2) | 645 | 0 | 0 | 0 |
| dispatch-courier-service (S3) | 253 | 0 | 0 | 0 |
| tracking-reporting-service (S4) | 58 | 0 | 0 | 0 |
| notification-integration-service (S5) | 398 | 0 | 0 | 0 |
| merchant-order-webhook-e2e (subsequent successful run) | 2 | 0 | 0 | 0 |

Docker-backed Kafka/PostgreSQL service tests ran successfully, including outbox atomicity/restart, consumer poison-record recovery, durable notification retry and signed webhook delivery/replay. Real gRPC server/channel tests also passed. The subsequent successful `MerchantApprovalGapIT` run verified both E2E business scenarios. The earlier failed full reactor log finished at `2026-11-09T19:07:37+05:30` after 44 minutes and remains historical evidence of the image-fetch failure. Existing Maven platform-encoding and test-only missing-SLF4J-provider warnings were not suppressed.

Focused tests ran before advancing each phase: shared validation/factory/concurrency/cleanup tests, real S4 malformed-record recovery, S1 gRPC propagation/security/deadline scenarios, S2 atomicity/Kafka/restart/retry/legacy coverage, S3 PostgreSQL lease recovery and worker retry coverage, S5 durable notification and signed webhook/replay coverage. Final focused regression checks also passed. Development compilation/assertion failures were corrected before the final runs; no tests were removed or disabled.

The earlier full reactor command (which returned BUILD FAILURE as described above) was:

```powershell
mvn --batch-mode --no-transfer-progress -f backend/pom.xml -fae -DrunE2E verify
```

Local ignored logs: `backend/target/143-phase2b-final.log`, `143-phase2b-grpc-final.log`, `143-phase3-tests.log`, `143-phase3-final.log`, `143-phase4-tests.log` (intermediate failed S5 test compilation), `143-phase4-s5-final.log`, `143-final-focused.log`, `143-full-reactor.log`.

`git diff --check` passed. The log call-site audit found no remaining matches for raw `getMessage()`/throwable log arguments and the audited unvalidated external-reference/JWT-claim patterns. Sentinel tests cover private malformed payloads, nested processing failures, invalid credentials and unexpected gRPC handler messages. This verifies the tested application log paths, not every operator-configurable third-party debug facility.

`python docs/api/build_openapi.py --check` **fails** with exactly the known four S1 controller/spec inventory omissions: GET and DELETE `/api/v1/admin/merchants/{applicationId}/api-key`, PATCH `/api/v1/admin/merchants/{applicationId}/suspend`, PATCH `/api/v1/admin/merchants/{applicationId}/reinstate`. These were explicitly excluded and are not repaired. No event JSON schema, proto or backend parent POM changes.

## Remaining risks and ownership

- The Kafka image-fetch blocker is resolved. Service-level Docker integration tests and both cross-service opt-in E2E scenarios passed across the runs described above; the original full reactor attempt remains a historical failure.

- Collector/exporter deployment and sampling policy are operational work; this change enables propagation and SDK spans without configuring an exporter.
- Missing legacy metadata cannot reconstruct the original trace. Stable typed event/proof IDs supply correlation and each processing attempt starts a new root trace.
- Arbitrary executors and DeferredResult producers require explicit context snapshots; MVC Callable and response redispatch are covered. Parser failures before application dispatch cannot guarantee response correlation.
- Do not enable HTTP wire, SQL parameter or framework TRACE logging on sensitive traffic. Application-level diagnostic allowlists cannot protect arbitrary operator-added logging.
- Browser CORS/header policy is unchanged; external webhook destinations receive no diagnostic headers. Existing credential policies remain in their original clients.
- S2/S3/S5 owners should review and sequence their own additive migrations; deployment, independent review and issue acceptance remain outstanding owner activities. Issue #222 retains broader cross-service error body/status standards. No owner has been contacted and no publication performed.

## Exact working-tree inventory

This includes the pre-existing Phase 1/2A changes, not just this task's additions. Paths are repository-relative. `M` means modified tracked file and `??` means new untracked file. Generated target logs are ignored build artifacts and are not source additions.

52 modified tracked files; 27 new untracked files (including this report).

```text
 M backend/api-gateway/README.md
 M backend/api-gateway/pom.xml
 M backend/api-gateway/src/main/java/com/zentro/gateway/filters/CorrelationIdFilter.java
 M backend/api-gateway/src/main/java/com/zentro/gateway/security/JwtTokenValidator.java
 M backend/api-gateway/src/main/resources/application.yml
 M backend/api-gateway/src/test/java/com/zentro/gateway/filters/CorrelationIdFilterTest.java
 M backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/courier/repository/ProofRepository.java
 M backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/courier/service/ProofCompletionWorker.java
 M backend/dispatch-courier-service/src/main/resources/application.yml
 M backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/courier/repository/ProofPostgresIntegrationTest.java
 M backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/courier/service/ProofCompletionWorkerTest.java
 M backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/security/GrpcSecurityInterceptor.java
 M backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/server/IdentityPartnerGrpcServer.java
 M backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/service/IdentityPartnerGrpcService.java
 M backend/identity-partner-service/src/main/resources/application.yml
 M backend/libs/zentro-security-starter/pom.xml
 M backend/libs/zentro-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 M backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/config/KafkaConsumerConfig.java
 M backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/delivery/service/NotificationDispatcher.java
 M backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/notification/repository/NotificationRepository.java
 M backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/webhook/config/WebhookConfig.java
 M backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/webhook/repository/WebhookDeliveryRepository.java
 M backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/webhook/service/WebhookDeliveryService.java
 M backend/notification-integration-service/src/main/resources/application.yml
 M backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/delivery/service/NotificationDispatcherTest.java
 M backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/integration/WebhookDeliveryIntegrationTest.java
 M backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/webhook/service/WebhookDeliveryServiceTest.java
 M backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/integration/IdentityMerchantClient.java
 M backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/outbox/publisher/OutboxPublisher.java
 M backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/outbox/repository/OutboxRepository.java
 M backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/parcel/repository/ParcelStatusRepository.java
 M backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/parcel/service/MerchantOrderService.java
 M backend/parcel-pricing-service/src/main/resources/application.yml
 M backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/integration/OutboxKafkaIntegrationTest.java
 M backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/integration/OutboxRestartRecoveryIntegrationTest.java
 M backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/outbox/publisher/OutboxPublisherTest.java
 M backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/config/KafkaConsumerConfig.java
 M backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/exception/GlobalTrackingExceptionHandler.java
 M backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/messaging/consumer/TrackingKafkaConsumer.java
 M backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/tracking/service/TrackingStreamService.java
 M backend/tracking-reporting-service/src/main/resources/application.yml
 M backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/integration/TrackingKafkaConsumerIntegrationTest.java
 M docs/api/README.md
 M docs/api/build_openapi.py
 M docs/api/merchant-webhooks.md
 M docs/api/openapi/dispatch.openapi.json
 M docs/api/openapi/identity.openapi.json
 M docs/api/openapi/notifications.openapi.json
 M docs/api/openapi/parcel.openapi.json
 M docs/api/openapi/tracking.openapi.json
 M docs/api/parcel-events.md
 M docs/api/proof-of-delivery.md
?? backend/api-gateway/src/test/java/com/zentro/gateway/routes/GatewayHttpPropagationTests.java
?? backend/dispatch-courier-service/src/main/resources/db/migration/V3__proof_propagation_metadata.sql
?? backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/security/GrpcPropagationInterceptor.java
?? backend/identity-partner-service/src/test/java/com/zentro/identitypartner/grpc/IdentityPartnerGrpcPropagationTest.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/config/CorrelationAutoConfiguration.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/config/KafkaPropagationAutoConfiguration.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/config/PropagationAutoConfiguration.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/CorrelationIdFilter.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/InternalCorrelationInterceptor.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/KafkaPropagationInterceptor.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/PropagationContext.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/PropagationMetadata.java
?? backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/SafeKafkaErrorHandler.java
?? backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/CorrelationIdFilterTest.java
?? backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/HttpPropagationIntegrationTest.java
?? backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/InternalCorrelationInterceptorTest.java
?? backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/KafkaPropagationFactoriesTest.java
?? backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/PropagationContextTest.java
?? backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/SafeKafkaErrorHandlerTest.java
?? backend/notification-integration-service/src/main/resources/db/migration/V3__delivery_propagation_metadata.sql
?? backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/integration/DurableNotificationPropagationIntegrationTest.java
?? backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/support/PropagationTestSupport.java
?? backend/parcel-pricing-service/src/main/resources/db/migration/V9__outbox_propagation_metadata.sql
?? backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/exception/GlobalTrackingExceptionHandlerSafeLoggingTest.java
?? backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/messaging/consumer/TrackingKafkaConsumerSafeLoggingTest.java
?? docs/architecture/observability.md
?? docs/test-reports/zd-068-event-correlation-tracing.md
```

### Preserved byte-for-byte from the starting working tree

- `backend/api-gateway/README.md`
- `backend/api-gateway/pom.xml`
- `backend/api-gateway/src/main/java/com/zentro/gateway/filters/CorrelationIdFilter.java`
- `backend/api-gateway/src/main/resources/application.yml`
- `backend/api-gateway/src/test/java/com/zentro/gateway/filters/CorrelationIdFilterTest.java`
- `backend/api-gateway/src/test/java/com/zentro/gateway/routes/GatewayHttpPropagationTests.java`
- `backend/dispatch-courier-service/src/main/resources/application.yml`
- `backend/identity-partner-service/src/main/resources/application.yml`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/config/CorrelationAutoConfiguration.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/CorrelationIdFilter.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/InternalCorrelationInterceptor.java`
- `backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/CorrelationIdFilterTest.java`
- `backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/HttpPropagationIntegrationTest.java`
- `backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/InternalCorrelationInterceptorTest.java`
- `backend/notification-integration-service/src/main/resources/application.yml`
- `backend/parcel-pricing-service/src/main/resources/application.yml`
- `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/exception/GlobalTrackingExceptionHandler.java`
- `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/messaging/consumer/TrackingKafkaConsumer.java`
- `backend/tracking-reporting-service/src/main/resources/application.yml`
- `backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/exception/GlobalTrackingExceptionHandlerSafeLoggingTest.java`
- `backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/messaging/consumer/TrackingKafkaConsumerSafeLoggingTest.java`
- `docs/api/README.md`
- `docs/api/build_openapi.py`
- `docs/api/openapi/dispatch.openapi.json`
- `docs/api/openapi/identity.openapi.json`
- `docs/api/openapi/notifications.openapi.json`
- `docs/api/openapi/parcel.openapi.json`
- `docs/api/openapi/tracking.openapi.json`

### Existing Phase 1/2A files extended in this task

- `backend/libs/zentro-security-starter/pom.xml`
- `backend/libs/zentro-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

### New work relative to the starting snapshot

- `backend/api-gateway/src/main/java/com/zentro/gateway/security/JwtTokenValidator.java`
- `backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/courier/repository/ProofRepository.java`
- `backend/dispatch-courier-service/src/main/java/com/zentro/dispatchcourier/courier/service/ProofCompletionWorker.java`
- `backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/courier/repository/ProofPostgresIntegrationTest.java`
- `backend/dispatch-courier-service/src/test/java/com/zentro/dispatchcourier/courier/service/ProofCompletionWorkerTest.java`
- `backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/security/GrpcSecurityInterceptor.java`
- `backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/server/IdentityPartnerGrpcServer.java`
- `backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/service/IdentityPartnerGrpcService.java`
- `backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/config/KafkaConsumerConfig.java`
- `backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/delivery/service/NotificationDispatcher.java`
- `backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/notification/repository/NotificationRepository.java`
- `backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/webhook/config/WebhookConfig.java`
- `backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/webhook/repository/WebhookDeliveryRepository.java`
- `backend/notification-integration-service/src/main/java/com/zentro/notificationintegration/webhook/service/WebhookDeliveryService.java`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/delivery/service/NotificationDispatcherTest.java`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/integration/WebhookDeliveryIntegrationTest.java`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/webhook/service/WebhookDeliveryServiceTest.java`
- `backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/integration/IdentityMerchantClient.java`
- `backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/outbox/publisher/OutboxPublisher.java`
- `backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/outbox/repository/OutboxRepository.java`
- `backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/parcel/repository/ParcelStatusRepository.java`
- `backend/parcel-pricing-service/src/main/java/com/zentro/parcelpricing/parcel/service/MerchantOrderService.java`
- `backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/integration/OutboxKafkaIntegrationTest.java`
- `backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/integration/OutboxRestartRecoveryIntegrationTest.java`
- `backend/parcel-pricing-service/src/test/java/com/zentro/parcelpricing/outbox/publisher/OutboxPublisherTest.java`
- `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/config/KafkaConsumerConfig.java`
- `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/tracking/service/TrackingStreamService.java`
- `backend/tracking-reporting-service/src/test/java/com/zentro/trackingreporting/integration/TrackingKafkaConsumerIntegrationTest.java`
- `docs/api/merchant-webhooks.md`
- `docs/api/parcel-events.md`
- `docs/api/proof-of-delivery.md`
- `backend/dispatch-courier-service/src/main/resources/db/migration/V3__proof_propagation_metadata.sql`
- `backend/identity-partner-service/src/main/java/com/zentro/identitypartner/grpc/security/GrpcPropagationInterceptor.java`
- `backend/identity-partner-service/src/test/java/com/zentro/identitypartner/grpc/IdentityPartnerGrpcPropagationTest.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/config/KafkaPropagationAutoConfiguration.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/config/PropagationAutoConfiguration.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/KafkaPropagationInterceptor.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/PropagationContext.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/PropagationMetadata.java`
- `backend/libs/zentro-security-starter/src/main/java/com/zentro/security/observability/SafeKafkaErrorHandler.java`
- `backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/KafkaPropagationFactoriesTest.java`
- `backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/PropagationContextTest.java`
- `backend/libs/zentro-security-starter/src/test/java/com/zentro/security/observability/SafeKafkaErrorHandlerTest.java`
- `backend/notification-integration-service/src/main/resources/db/migration/V3__delivery_propagation_metadata.sql`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/integration/DurableNotificationPropagationIntegrationTest.java`
- `backend/notification-integration-service/src/test/java/com/zentro/notificationintegration/support/PropagationTestSupport.java`
- `backend/parcel-pricing-service/src/main/resources/db/migration/V9__outbox_propagation_metadata.sql`
- `docs/test-reports/zd-068-event-correlation-tracing.md`

### Tracked diff summary

```text
 backend/api-gateway/README.md                      |   2 +
 backend/api-gateway/pom.xml                        |   1 +
 .../gateway/filters/CorrelationIdFilter.java       |  18 +-
 .../zentro/gateway/security/JwtTokenValidator.java |   7 +-
 .../api-gateway/src/main/resources/application.yml |  11 +
 .../gateway/filters/CorrelationIdFilterTest.java   |  44 ++
 .../courier/repository/ProofRepository.java        |  25 +-
 .../courier/service/ProofCompletionWorker.java     |  29 +-
 .../src/main/resources/application.yml             |  15 +
 .../repository/ProofPostgresIntegrationTest.java   |  16 +
 .../courier/service/ProofCompletionWorkerTest.java |  25 +
 .../grpc/security/GrpcSecurityInterceptor.java     |   2 +-
 .../grpc/server/IdentityPartnerGrpcServer.java     |  11 +
 .../grpc/service/IdentityPartnerGrpcService.java   |   6 +-
 .../src/main/resources/application.yml             |  11 +
 backend/libs/zentro-security-starter/pom.xml       |   3 +
 ...rk.boot.autoconfigure.AutoConfiguration.imports |   3 +
 .../config/KafkaConsumerConfig.java                |  14 +-
 .../delivery/service/NotificationDispatcher.java   |  46 +-
 .../repository/NotificationRepository.java         |  26 +-
 .../webhook/config/WebhookConfig.java              |  14 +-
 .../repository/WebhookDeliveryRepository.java      |  28 +-
 .../webhook/service/WebhookDeliveryService.java    |  97 +--
 .../src/main/resources/application.yml             |  14 +
 .../service/NotificationDispatcherTest.java        |  24 +
 .../WebhookDeliveryIntegrationTest.java            |  10 +-
 .../service/WebhookDeliveryServiceTest.java        |  20 +
 .../integration/IdentityMerchantClient.java        |   2 +-
 .../outbox/publisher/OutboxPublisher.java          |  60 +-
 .../outbox/repository/OutboxRepository.java        |  15 +-
 .../parcel/repository/ParcelStatusRepository.java  |  11 +-
 .../parcel/service/MerchantOrderService.java       |   3 +-
 .../src/main/resources/application.yml             |  16 +
 .../integration/OutboxKafkaIntegrationTest.java    |  19 +
 .../OutboxRestartRecoveryIntegrationTest.java      |  28 +-
 .../outbox/publisher/OutboxPublisherTest.java      |  37 +
 .../config/KafkaConsumerConfig.java                |  13 +-
 .../exception/GlobalTrackingExceptionHandler.java  |   2 +-
 .../messaging/consumer/TrackingKafkaConsumer.java  |  19 +-
 .../tracking/service/TrackingStreamService.java    |   4 +-
 .../src/main/resources/application.yml             |   9 +
 .../TrackingKafkaConsumerIntegrationTest.java      |  19 +
 docs/api/README.md                                 |   2 +-
 docs/api/build_openapi.py                          |  14 +-
 docs/api/merchant-webhooks.md                      |   9 +
 docs/api/openapi/dispatch.openapi.json             | 390 ++++++----
 docs/api/openapi/identity.openapi.json             | 852 ++++++++++++++-------
 docs/api/openapi/notifications.openapi.json        | 290 ++++---
 docs/api/openapi/parcel.openapi.json               | 434 +++++++----
 docs/api/openapi/tracking.openapi.json             | 170 ++--
 docs/api/parcel-events.md                          |  12 +
 docs/api/proof-of-delivery.md                      |   9 +
 52 files changed, 2051 insertions(+), 910 deletions(-)
```

The tracked diff excludes new untracked files listed above. No commit, push, merge or PR creation occurred.
