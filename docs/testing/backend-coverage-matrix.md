# Backend coverage evidence — Issue #351

Actual JaCoCo LINE counters from local XML reports. Base `9275ea8ccb3170097757e332525125264769d21f` plus Issue #351 changes. Docker-dependent scenarios are skipped; S3 additionally has two Docker environment errors and its report is partial. S2 was rerun with jacoco.append=false because its initial exec file predated this task. These measurements do not establish full integration coverage or accepted exceptions. The documented target remains at least 70% for service and security layers (README.md). No enforcement gate is added here.

Reproduce summary: `python docs/testing/build_issue351_coverage.py` after the commands in issue-351-implementation.md. Layer selection is explicit: package path segment `service` or `security`; packages such as config, reporting and grpc are retained separately below. The shared starter uses com/zentro/security including its configuration and observability packages, so inspect its package rows rather than interpreting the aggregate as JWT-only coverage. Generated protobuf classes are included in module totals; no exclusions were introduced.

| Module | All lines | Service layer | Security layer | Report |
|---|---|---|---|---|
| libs/zentro-security-starter | 183/217 (84.33%) | N/A (no measured lines) | 183/217 (84.33%) | `backend/libs/zentro-security-starter/target/site/jacoco/index.html` |
| api-gateway | 240/243 (98.77%) | N/A (no measured lines) | 45/45 (100.00%) | `backend/api-gateway/target/site/jacoco/index.html` |
| identity-partner-service | 1137/2233 (50.92%) | 317/400 (79.25%) | 72/75 (96.00%) | `backend/identity-partner-service/target/site/jacoco/index.html` |
| parcel-pricing-service | 1093/1421 (76.92%) | 395/477 (82.81%) | N/A (no measured lines) | `backend/parcel-pricing-service/target/site/jacoco/index.html` |
| dispatch-courier-service | 399/614 (64.98%) | 287/313 (91.69%) | 29/29 (100.00%) | `backend/dispatch-courier-service/target/site/jacoco/index.html` |
| tracking-reporting-service | 834/1062 (78.53%) | 232/295 (78.64%) | 56/61 (91.80%) | `backend/tracking-reporting-service/target/site/jacoco/index.html` |
| notification-integration-service | 1081/1425 (75.86%) | 350/406 (86.21%) | 99/103 (96.12%) | `backend/notification-integration-service/target/site/jacoco/index.html` |

## Prioritized owner-authored test recommendations

- S1 (#350): exercise missing/inactive identities and gRPC service error mapping; distinguish generated protobuf boilerplate from business-layer gaps without excluding it here.
- S2 (#353): add meaningful pricing/status transition and idempotent-outbox failure cases for missed service lines; run PostgreSQL boundary tests before interpreting the fresh 82.81% service result as acceptance.
- S3 (#354): rerun AssignmentPostgresIntegrationTest and AssignmentToCourierScenarioTest with Docker; verify lock/concurrency and actual producer payloads rather than treating mocked eligibility as provider acceptance.
- S4 (#357/#358): run event projection/recount and pagination SQL cases; add missed service/exception branches and separately classify reporting packages for the gate.
- S5 (#356): exercise provider outage/retry recovery and consumer/database failure paths with Kafka/PostgreSQL; retain the published retry/error contracts.
- Shared/gateway (#351): investigate the uncovered configuration/guard paths and upstream sanitizer branches; current executed SSE tests use a stub upstream, so live token-expiry/reconnect remains separate.

## Package evidence

| Module | Package | Covered / total | Uncovered lines | Recommendation |
|---|---|---|---|---|
| libs/zentro-security-starter | `com/zentro/security/observability` | 141/159 (88.68%) | 18 | Owner-authored failure/authorization/business boundary tests recommended |
| libs/zentro-security-starter | `com/zentro/security/ownership` | 3/3 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| libs/zentro-security-starter | `com/zentro/security/error` | 8/8 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| libs/zentro-security-starter | `com/zentro/security/config` | 31/47 (65.96%) | 16 | Owner-authored failure/authorization/business boundary tests recommended |
| api-gateway | `com/zentro/gateway` | 1/2 (50.00%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| api-gateway | `com/zentro/gateway/config` | 65/66 (98.48%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| api-gateway | `com/zentro/gateway/security` | 45/45 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| api-gateway | `com/zentro/gateway/exception` | 46/46 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| api-gateway | `com/zentro/gateway/constants` | 2/2 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| api-gateway | `com/zentro/gateway/filters` | 81/82 (98.78%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/grpc/server` | 24/29 (82.76%) | 5 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/merchant/service` | 104/147 (70.75%) | 43 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/grpc/security` | 72/75 (96.00%) | 3 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/user/validation` | 43/43 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner/merchant/dto` | 9/9 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner/user/service` | 111/134 (82.84%) | 23 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/courier/controller` | 12/12 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner/courier/dto` | 3/3 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner/user/exception` | 54/58 (93.10%) | 4 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/user/controller` | 13/13 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner/grpc/service` | 33/33 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner/user/repository` | 0/13 (0.00%) | 13 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/contracts/identity/v1` | 376/944 (39.83%) | 568 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/user/audit` | 12/13 (92.31%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/merchant/controller` | 24/24 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner` | 1/1 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| identity-partner-service | `com/zentro/identitypartner/user/dto` | 24/25 (96.00%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/contracts/parcel/v1` | 153/571 (26.80%) | 418 | Owner-authored failure/authorization/business boundary tests recommended |
| identity-partner-service | `com/zentro/identitypartner/courier/service` | 69/86 (80.23%) | 17 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing` | 1/1 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| parcel-pricing-service | `com/zentro/parcelpricing/pricing/dto` | 5/5 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| parcel-pricing-service | `com/zentro/parcelpricing/pricing/service` | 89/150 (59.33%) | 61 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/pricing/rules` | 73/73 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| parcel-pricing-service | `com/zentro/parcelpricing/parcel/service` | 306/327 (93.58%) | 21 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/outbox/repository` | 3/31 (9.68%) | 28 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/exception` | 161/176 (91.48%) | 15 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/validation` | 27/27 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| parcel-pricing-service | `com/zentro/parcelpricing/pricing/controller` | 108/121 (89.26%) | 13 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/parcel/controller` | 49/52 (94.23%) | 3 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/parcel/dto` | 39/39 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| parcel-pricing-service | `com/zentro/parcelpricing/integration` | 31/39 (79.49%) | 8 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/outbox/publisher` | 73/81 (90.12%) | 8 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/config` | 35/49 (71.43%) | 14 | Owner-authored failure/authorization/business boundary tests recommended |
| parcel-pricing-service | `com/zentro/parcelpricing/parcel/entity` | 73/73 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| parcel-pricing-service | `com/zentro/parcelpricing/parcel/repository` | 20/177 (11.30%) | 157 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier/dispatch/service` | 123/136 (90.44%) | 13 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier/dispatch/repository` | 1/92 (1.09%) | 91 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier/courier/repository` | 5/38 (13.16%) | 33 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier` | 1/1 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| dispatch-courier-service | `com/zentro/dispatchcourier/event` | 0/56 (0.00%) | 56 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier/dispatch/controller` | 8/11 (72.73%) | 3 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier/exception` | 38/38 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| dispatch-courier-service | `com/zentro/dispatchcourier/courier/dto` | 23/23 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| dispatch-courier-service | `com/zentro/dispatchcourier/courier/controller` | 6/8 (75.00%) | 2 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier/security` | 29/29 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| dispatch-courier-service | `com/zentro/dispatchcourier/courier/service` | 164/177 (92.66%) | 13 | Owner-authored failure/authorization/business boundary tests recommended |
| dispatch-courier-service | `com/zentro/dispatchcourier/courier/entity` | 1/5 (20.00%) | 4 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/tracking/controller` | 25/26 (96.15%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/messaging/idempotency` | 0/27 (0.00%) | 27 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/tracking/service` | 232/295 (78.64%) | 63 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/tracking/repository` | N/A (no measured lines) | 0 | No uncovered lines in this run; branch completeness not implied |
| tracking-reporting-service | `com/zentro/trackingreporting/security` | 56/61 (91.80%) | 5 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/config` | 9/14 (64.29%) | 5 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/tracking/dto` | 7/7 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| tracking-reporting-service | `com/zentro/trackingreporting` | 1/3 (33.33%) | 2 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/exception` | 38/59 (64.41%) | 21 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/tracking/entity` | 120/130 (92.31%) | 10 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/graphql/config` | 33/33 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| tracking-reporting-service | `com/zentro/trackingreporting/graphql/exception` | 27/47 (57.45%) | 20 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/messaging/consumer` | 126/139 (90.65%) | 13 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/reporting` | 0/30 (0.00%) | 30 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/graphql/endpoint` | 156/187 (83.42%) | 31 | Owner-authored failure/authorization/business boundary tests recommended |
| tracking-reporting-service | `com/zentro/trackingreporting/graphql/dto` | 4/4 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| notification-integration-service | `com/zentro/notificationintegration/webhook/entity` | 10/11 (90.91%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/config` | 39/51 (76.47%) | 12 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/event` | 16/20 (80.00%) | 4 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/dto/request` | 0/1 (0.00%) | 1 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/messaging/event` | 107/109 (98.17%) | 2 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/delivery/sms` | 14/16 (87.50%) | 2 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/messaging/consumer` | 67/75 (89.33%) | 8 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/service` | 226/272 (83.09%) | 46 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/exception` | 18/38 (47.37%) | 20 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/validation` | 36/39 (92.31%) | 3 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/notification/recipient` | 11/11 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| notification-integration-service | `com/zentro/notificationintegration/notification/entity` | 6/6 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| notification-integration-service | `com/zentro/notificationintegration/admin/dto` | 2/5 (40.00%) | 3 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/controller` | 0/22 (0.00%) | 22 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/notification/template` | 87/98 (88.78%) | 11 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/security` | 99/103 (96.12%) | 4 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/dto/response` | 1/14 (7.14%) | 13 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/utils` | 56/58 (96.55%) | 2 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/notification/service` | 75/82 (91.46%) | 7 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/webhook/repository` | 7/110 (6.36%) | 103 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/config` | 43/52 (82.69%) | 9 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/admin/controller` | 25/27 (92.59%) | 2 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration` | 1/1 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| notification-integration-service | `com/zentro/notificationintegration/delivery/service` | 49/52 (94.23%) | 3 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/notification/repository` | 3/54 (5.56%) | 51 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/delivery/provider` | 3/3 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |
| notification-integration-service | `com/zentro/notificationintegration/webhook/client` | 60/75 (80.00%) | 15 | Owner-authored failure/authorization/business boundary tests recommended |
| notification-integration-service | `com/zentro/notificationintegration/notification/preference` | 20/20 (100.00%) | 0 | No uncovered lines in this run; branch completeness not implied |

## Handoff requirements for #352 — Niro0526

This is a local handoff document; no message or issue mutation was sent. Add consistent JaCoCo prepare-agent/report/check execution for all five services, gateway and starter; publish XML/HTML with candidate SHA and test/skipped counts. Define service/security layer package membership explicitly (including security-related config and reporting service packages), then enforce the existing 70% line target separately for each applicable layer. Preserve Surefire/Failsafe argLine instrumentation, merge unit/integration data intentionally, use a fresh per-candidate exec file, and fail when expected reports/counters are absent. Do not silently average a weak service with a strong one.

Domain owners #350/#353/#354/#356/#357/#358 must supply meaningful missing business tests. New instrumentation/build gates belong to #352; POMs and workflows were not edited. Record an explicitly approved exception with owner/rationale if a layer cannot meet target; no such exception is claimed here. A passing verify is not proof of 70% enforcement.
