# ZD-047: S4/S5 Service Contract Gap Register

Status: audit artifact prepared; PR, independent review and merge are pending.

Issue: [#122](https://github.com/pirathee587/Zentro/issues/122)
Target branch: `develop`
Audit base: `origin/develop` at `42131fd` (2026-11-11)
Related implementation: PR [#114](https://github.com/pirathee587/Zentro/pull/114), merged as `2c9d44e0fcc2648c55f624bf073b9b0f64987c11`

## Scope and method

This register compares the WARP.md architecture baseline with the checked-in
REST, Kafka, gRPC, gateway, security, schema and database-boundary artifacts for
all five services. GitHub issues #4, #24, #32 and #58, and merged PR #114,
were read as context. Only mismatches reproduced from source or checked-in
contracts are listed as confirmed gaps. No service code, event schema, route or
database was changed for this audit.

## Confirmed gaps

| ID | Severity | Expected behavior | Actual behavior and evidence | Impact | Responsible owner | Recommended fix |
|---|---|---|---|---|---|---|
| ZD-047-01 | Major | S4 is the Tracking & Reporting service and WARP.md assigns it reports and analytics. A routed reports contract should either be implemented and documented, or the route should not be presented as an available service surface. | The gateway routes `/api/v1/reports/**` to S4 in `backend/api-gateway/src/main/resources/application.yml:32-35`. S4 has only tracking controllers in `backend/tracking-reporting-service/src/main/java/com/zentro/trackingreporting/tracking/controller/CustomerTrackingController.java:17-18` and `PublicTrackingController.java:24-25`; no reports controller exists. The API index calls the prefix reserved with no active report controller (`docs/api/README.md:10`), and the projection evidence says no reports endpoint is implemented (`docs/events/projections.md:3`). | Dispatcher/reporting clients receive an authenticated routed request but no reports operation. The intended analytics projection cannot be consumed through a stable REST contract, and clients cannot distinguish an approved API from a reserved path. | S4/reporting API owner; the current artifact names @Deadsecnote1 for the separate reports contract/dashboard follow-up (`docs/events/projections.md:105`). Product/API approval is still required. | Approve the reports contract first, then implement the S4 controller, DTO/OpenAPI entry, gateway authorization and negative/positive tests together. Until approved, remove or explicitly reserve the gateway route without presenting it as an available API. Do not reconstruct identities or read another service's database. |
| ZD-047-02 | Major | WARP.md assigns audit-log responsibility to S5 and the gateway exposes a Dispatcher administration path for that capability. A routed audit-log contract should have an S5 owner, storage model and secured endpoint, or the route should be removed until approved. | The gateway routes `/api/v1/admin/audit-logs/**` to S5 (`backend/api-gateway/src/main/resources/application.yml:38-41`). S5's concrete controllers are the merchant webhook controller (`MerchantWebhookController.java:32-33`) and the integration-attempt controller (`IntegrationAttemptController.java:16-17`); neither maps `/api/v1/admin/audit-logs`. No `audit` implementation or audit-log migration was found under S5 main sources. The API inventory explicitly classifies this as a controllerless gateway prefix (`docs/api/README.md:63`). | Dispatcher clients cannot retrieve the audit logs implied by the routed administrative surface. The route creates a contract boundary without an implementation and can produce misleading operational availability. | S5 notification/integration owner and the security/audit owner; no named implementation owner was verified in the inspected source or issue records. | Define the audit-log event, retention, redaction, pagination and Dispatcher permission contract before implementation. Then add an S5-owned migration, service/controller, OpenAPI entry, gateway test and authorization/failure tests. Otherwise remove the unused route until that decision is approved. |

## Verified no-gap areas

| Area | Verification result |
|---|---|
| S2 -> S4/S5 Kafka topics | `parcel.created` and `parcel.status.updated` are the documented producer topics. S4, S5 notifications and S5 merchant webhooks each consume those topics with independent consumer groups. The event envelope and parcel schemas are shared; `merchantId` is additive and PR #114's merged implementation is present. |
| Kafka safety | S4 and both S5 consumers validate event identity, topic/type and schema version, de-duplicate in service-owned tables, and distinguish malformed records from database failures. Existing tests cover duplicate, malformed, unsupported-version and failure paths. |
| S4 REST and GraphQL security | Customer/merchant tracking is subject-scoped; live streams require authentication; GraphQL is read-only and Dispatcher-wide queries are role checked. No separate ADMIN role is introduced. |
| S5 REST and webhook security | Merchant webhook operations are token-subject scoped and `MERCHANT`-protected. Dispatcher outcome history is `DISPATCHER`-protected. PR #114's endpoint validation, encrypted secret handling, signing and retry behavior remain in the current code. |
| Gateway ownership | Existing active S4 tracking/GraphQL and S5 webhook/outcome-history operations route to their owning service. The two gaps above are reserved/controllerless prefixes, not cross-service routing or database access. |
| gRPC | The repository contains approved proto contracts for S1/S2 interactions, but no WARP.md requirement or approved S4/S5 gRPC contract was found. This is not recorded as a gap. |
| Database boundaries | S4 projection/history data and S5 notification/webhook data are owned by their respective schemas. No confirmed cross-service database query was found in the audited S4/S5 paths. |

## Unverified items

These are not confirmed gaps and are intentionally excluded from the findings:

- Deployed gateway-to-service routing, private service-port isolation, live Keycloak configuration and production authorization claims.
- Kafka broker retention, partition/replication settings, rebalance behavior and production outage recovery.
- Provider/receiver exactly-once behavior across a crash after an outbound send and before the database commit.
- Production migration state and whether an operator has separately implemented audit-log or reports infrastructure outside this repository.
- Independent reviewer approval, PR creation, merge, merged SHA for ZD-047, and issue closure.

## Verification record

The audit used the checked-in source and documentation at `42131fd`, plus the
public GitHub records for #4, #24, #32, #58, #122 and PR #114. PR #114 is
confirmed merged into `develop` at
`2c9d44e0fcc2648c55f624bf073b9b0f64987c11`; ZD-047 itself has no PR or merge
SHA at the time of this audit.

## Actual verification results

- `mvn --batch-mode -pl api-gateway -Dtest=GatewayInventoryTests test`: passed, 2 tests, 0 failures.
- `mvn --batch-mode -pl tracking-reporting-service -am test`: blocked before S4 by the existing shared-starter `RepositoryConfigurationGuardTest`; it found non-empty credential placeholders in the pre-existing `infra/keycloak/.env.example` working-tree change. That file was not modified by this audit.
- After installing the shared starter with tests skipped, `mvn --batch-mode -pl tracking-reporting-service test`: passed, 135 tests, 0 failures.
- `mvn --batch-mode -pl notification-integration-service test`: compiled S5 but JUnit discovery failed on a stale generated test class from an old package (`NoClassDefFoundError`); the current `messaging.event.ParcelEventParserTest` itself passed 24 tests.
- After `mvn --batch-mode clean test`, S5 integration tests started with Kafka/PostgreSQL Testcontainers and notification integration cases passed, but the run stalled during a later Kafka container startup and was terminated. No final S5 suite result or full backend reactor result is claimed.

This document does not mark ZD-047 complete. Completion still requires a
focused PR targeting `develop`, actual test evidence, independent reviewer
approval and the merged commit SHA, as required by issue #122.

Closes #122