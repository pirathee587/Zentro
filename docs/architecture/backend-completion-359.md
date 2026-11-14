# Backend technical completion appendix — Issue #359

Status: PARTIAL. This describes repository implementation at base `1f1b766` and
Main 10 acceptance additions. It is not a deployed-topology or release approval.

## Service and deployment architecture

```mermaid
flowchart LR
  Client[Backend client] --> Gateway[API gateway]
  Keycloak[Keycloak realm zentro] -. signed JWT / JWKS .-> Gateway
  Gateway --> S1[S1 Identity / Partner]
  Gateway --> S2[S2 Parcel / Pricing / Receipt]
  Gateway --> S3[S3 Dispatch / Courier / Proof]
  Gateway --> S4[S4 Tracking / Reports]
  Gateway --> S5[S5 Notification / Webhook]
  S3 -->|identity HTTP| S1
  S3 -->|SERVICE completion HTTP| S2
  S2 -->|transactional outbox| Kafka[Kafka]
  S3 -->|assignment outbox when enabled| Kafka
  Kafka --> S4
  Kafka --> S5
  S1 --> D1[(identity DB)]
  S2 --> D2[(parcel_pricing DB)]
  S3 --> D3[(dispatch_courier DB)]
  S4 --> D4[(tracking_reporting DB)]
  S5 --> D5[(notification_integration DB)]
```

Candidate enumeration/claim integration is missing; no successful S2→S3 candidate
arrow is claimed. S2 owns lifecycle/history writes; S3 successful proof completion
uses restricted SERVICE credentials. Gateway and each service validate tokens
independently. Kubernetes/Helm placeholders are not deployment evidence.

```mermaid
flowchart TB
  subgraph DisposableHost[Disposable local acceptance host]
    Test[Maven / JUnit shared fixture]
    Processes[Gateway 18080 and S1-S5 18081-18085 JVMs]
    PG[PostgreSQL container: five DBs and separate logins]
    KC[Keycloak container: imported realm and test clients]
    Broker[Kafka container]
    Test --> Processes
    Processes --> PG
    Processes --> KC
    Processes --> Broker
  end
```

Ports bind loopback. Processes run from E2E `target`, not personal service working
directories. Host JVMs use packaged JARs; a single physical DB container does not
permit runtime cross-service SQL. Fixture read-only SQL verifies owning source facts.

## Physical relationship summary

The following relationships reflect Flyway migrations, not cross-service joins.
Tracking event rows are related by application parcel keys; no external database FK
is implied. Receipt objects/PDFs are generated from persisted booking/order facts,
not a new receipt table.

```mermaid
erDiagram
  identity_app_user ||--o| identity_courier_profile : app_user_id
  identity_app_user ||--o| identity_merchant_application : app_user_id
  identity_app_user ||--o{ identity_merchant_api_key : app_user_id
  parcel_parcel ||--o{ parcel_parcel_status_history : parcel_id
  dispatch_delivery_assignment ||--o| dispatch_proof_of_delivery : parcel_id
  notification_webhook_subscription ||--o{ notification_webhook_delivery : subscription_id
  notification_webhook_delivery ||--o{ notification_webhook_delivery_replay : delivery_id
```

Service-local outbox, assignment audit, processed-event and delivery projection
tables are additional independent facts. Verify full columns in
`backend/*/src/main/resources/db/migration`; never modify applied migrations.

## Contracts, security and contribution evidence

- [API inventory](../api/README.md), [OpenAPI artifacts](../api/openapi/),
  [parcel event contract](../api/parcel-events.md), [projection contract](../events/projections.md).
- [Role/permission inventory](../security/authorization-matrix.md),
  [final security acceptance](../security/backend-final-signoff.md),
  [production configuration](../security/production-configuration.md).
- [Operations](../operations/backend-runbook.md), [handover](../operations/backend-handover.md),
  [candidate evidence](../testing/backend-integration-evidence.md).

Authoritative contribution evidence is the actual GitHub PR and commit history.
PR #369 documents Main 05 assignment/proof changes; #365 S2 retrieval; #377
tracking/receipt changes; #378 report API changes. This appendix does not attribute
unverified personal effort, hours or authorship. Main 10 additions in this branch
are shared acceptance support, generic role/integration tests, evidence tooling,
owner requests and this backend documentation. Domain production remains unchanged.

Coverage percentages are UNVERIFIED here. Test counts and skips come from current
JUnit reports, not a coverage percentage estimate. Final deliverable requirements,
deployment facts and independent contribution/document sign-off remain UNVERIFIED;
no final project PDF or frontend deliverable is produced.
