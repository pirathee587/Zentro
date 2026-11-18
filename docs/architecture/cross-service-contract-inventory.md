# Cross-service contract inventory — Phase 4 verification

Updated from current source on 2026-11-18. Historical issue #351 evidence is retained below only where marked historical; Phase 4 runtime verification is recorded in the final task report.

## Current synchronous gRPC calls

| Caller | Receiver | Contract / purpose | Authentication |
|---|---|---|---|
| S2 Parcel & Pricing | S1 Identity & Partner | Merchant status and approval verification | `zentro-parcel-service` SERVICE JWT |
| S3 Dispatch & Courier | S1 Identity & Partner | Courier profile, eligibility, and dispatcher directory | `zentro-dispatch-service` SERVICE JWT |
| S3 Dispatch & Courier | S2 Parcel & Pricing | `GetParcel` status check for assignment, POD completion, and courier status | `zentro-dispatch-service` SERVICE JWT; reads require `pod:read`, mutations require `pod:update` |
| S4 Tracking & Reporting | S2 Parcel & Pricing | Customer parcel/dashboard reads, public tracking, fee estimates | `zentro-tracking-service` SERVICE JWT |
| S4 Tracking & Reporting | S3 Dispatch & Courier | Customer delivery assignment summary | `zentro-tracking-service` SERVICE JWT |
| S5 Notification & Integration | S1 Identity & Partner | Merchant verification for webhook registration | `zentro-notification-service` SERVICE JWT |
| S5 Notification & Integration | S2 Parcel & Pricing | Customer-support parcel read tools | `zentro-notification-service` SERVICE JWT |

S1/S2/S3 listen on gRPC ports 9081/9082/9083. S4/S5 are outbound-only gRPC clients and expose no gRPC server ports. Docker service DNS uses the Compose container/service names; internal Keycloak requests use `keycloak:8080` while JWT issuer validation keeps the canonical development issuer. The four confidential service clients and their least-privilege mappings are reproducibly configured by `infra/keycloak/service-clients.json` and `infra/keycloak/provision_service_clients.py`. Inject each client secret separately through deployment secret storage or ignored local `.env` files; only `zentro-dispatch-service` receives POD permissions.

## Canonical protobuf v1

| Owner / provider | Operation / consumer | Request fields (wire tag) | Response fields (wire tag) | Existing evidence / gaps |
|---|---|---|---|---|
| S1 / IdentityPartnerGrpcService | GetMerchantStatus; no production gRPC consumer found | merchant_id:string(1), Keycloak subject | merchant_id:string(1), status:string(2) | IdentityPartnerGrpcServerIntegrationTest, IdentityPartnerGrpcResilienceIntegrationTest, ProtobufWireCompatibilityTest. Blank/absent application produces status NOT_FOUND in an otherwise successful RPC; disabled/suspended accounts produce SUSPENDED. |
| S1 / IdentityPartnerGrpcService | GetCourierProfile; no production gRPC consumer found | courier_id:string(1) | courier_id:string(1), active:bool(2) | Same server suites; GrpcSecurityInterceptorTest; missing/ineligible semantics must be reconciled with S1 owner before adding consumers. |
| S2 / declaration only | GetParcel; no runtime provider or consumer found | parcel_id:string(1) | parcel_id:string(1), tracking_number:string(2), status:string(3) | Generated in S1 build from shared proto; ProtobufWireCompatibilityTest pins wire bytes and unknown-field round trips. This does not establish a deployed S2 API. |

Definitions: `contracts/proto/identity_partner.proto`, `contracts/proto/parcel_pricing.proto`. Java generation: `backend/identity-partner-service/pom.xml` protoc-jar plugin, source directory `../../contracts/proto`, output under `target/generated-sources`. Compatibility tests pin existing tags and method names, accept additive unknown fields and preserve them on round trip. No proposed schema revision exists; this is baseline compatibility evidence, not proof of compatibility with an unapproved future interface. Removed tags must be reserved; published tags must never be reused or renumbered.

S1 authentication is `GrpcSecurityInterceptor`: configured nonblank `x-internal-token`, or validated bearer JWT with SERVICE or DISPATCHER realm authority. `x-internal-service` is caller context when using the internal credential, not an independent credential. Invalid credentials return UNAUTHENTICATED; insufficient JWT authority returns PERMISSION_DENIED. No new internal credentials or permissive fallbacks were introduced. `GrpcPropagationInterceptor` handles correlation/W3C propagation; IdentityPartnerGrpcPropagationTest exercises the actual gRPC server with test dependencies. Resilience tests exercise deadlines/cancellation; no production client deadline policy exists for a client that is not implemented. TLS/private-network deployment evidence remains unverified.

## Historical REST baseline (superseded by Phase 4)

The following table records the pre-migration synchronous REST clients and must not be read as the current transport inventory. Current internal service calls use gRPC as listed above.

| Provider / consumer | Operations / data consumed | Authentication / authorization | Deadlines / errors / tests |
|---|---|---|---|
| S1 / S2 IdentityMerchantClient | GET /api/v1/users/me; id,email,name,phone,accountStatus,merchantStatus,roles decoded; approval uses statuses | Merchant bearer forwarded; S1 identity rules; ACTIVE and APPROVED required in S2 | Connect 3s/read 5s; missing token 401, inactive/unapproved 403, upstream 401/403 retained, other failure 503. IdentityMerchantClientResilienceTest (S2). |
| S1 / S5 webhook IdentityMerchantClient | GET /api/v1/users/me; accountStatus,merchantStatus | Merchant bearer; ACTIVE and APPROVED required | 3s/5s; absent token 401; upstream 401/403 map to 403; other failure 503. IdentityMerchantClientResilienceTest (S5). Preserve the difference from S2. |
| S1 / S3 IdentityCourierClient | GET /api/v1/users/courier-profile; GET /api/v1/admin/couriers[?active=true]; GET /api/v1/admin/couriers/{id}; id,userId,name,active,accountStatus,assignedZone | Caller bearer; courier self-profile or dispatcher directory; effective eligibility active=true and accountStatus ACTIVE | 3s/5s; upstream 401/403 retained; requireEligible missing 404, ineligible 409, malformed/unavailable 503. IdentityCourierClientTest and IdentityCourierClientResilienceTest. |
| Identity token endpoint / S3 ServiceTokenProvider | OAuth client-credentials access token | Configured service credentials; no human ownership impersonation | ServiceTokenProviderTest, ServiceTokenProviderResilienceTest. Deployment credentials and provider availability unverified. |
| S3 ParcelStatusClient → S2 ParcelPricingGrpcClient | `CompleteParcelDelivery` gRPC; validates parcel ID and `DELIVERED` response | Keycloak SERVICE JWT with S2 `SERVICE` role and `pod:update` permission | Explicit 5-second deadline; invalid credentials invalidate the token cache; failed precondition is terminal and unavailable/deadline failures remain pending for the completion worker. |

Source paths: S2 `integration/IdentityMerchantClient.java`; S3 `courier/service/IdentityCourierClient.java`, `ServiceTokenProvider.java`; S5 `webhook/client/IdentityMerchantClient.java`, under their respective `backend/*/src/main/java/com/zentro/` trees. Provider fields and complete public operations are in [the operation matrix](../testing/backend-contract-matrix.md) and current OpenAPI components. RestClient requests use Boot observability; correlation propagation has existing shared-starter tests, but a live end-to-end provider/consumer correlation run is still missing. REST and gRPC responses differ; do not substitute one without owner agreement.

## Events

Shared envelope: eventId(UUID), eventType(string), occurredAt(date-time), aggregateId(UUID), payload(object); all required, extra envelope properties forbidden. `schemaVersion=1` is a Kafka header, not a new envelope field. Existing consumers support the documented legacy missing-header path. Kafka delivery is at least once; deduplication is by eventId. Correlation/trace headers remain transport metadata. No synchronous RPC deadline applies to an event; retry/DLT and ordering behavior are consumer responsibilities.

| Schema / operation | Producer / consumer | Payload fields and constraints | Evidence / gaps |
|---|---|---|---|
| parcel-created.schema.json / parcel.created | S2 outbox / S4 tracking, S5 notification and merchant webhook paths | trackingRef, status=CREATED, source=CUSTOMER_BOOKING or MERCHANT_ORDER, occurredAt required; optional merchantId; no extra fields | ParcelEventContractTest validates actual S2 output, negative UUID/missing/extra fields and privacy. Both consumer SchemaCompatibilityTest suites exercise v1/legacy/rejection. No customer/courier identity or address hydration contract. |
| parcel-status-updated.schema.json / parcel.status.updated | S2 outbox / S4, S5 | trackingRef,previousStatus,status,actorRole,occurredAt required; optional merchantId; failureReason required only for FAILED and forbidden otherwise; no extra payload properties | ParcelEventContractTest and both consumer compatibility suites. Preserve published fields and endpoint-specific state transitions. |
| delivery-assigned.schema.json / delivery.assigned | S3 AssignmentRepository outbox / downstream delivery integration not established | action=ASSIGNED,newCourierId(UUID),actorSubject required; optional nullable oldCourierId | Existing ParcelEventContractTest uses schema fixtures, not S3 producer capture. AssignmentToCourierScenarioTest supplies S3 lifecycle evidence; real broker consumer acceptance pending. |
| delivery-reassigned.schema.json / delivery.reassigned | S3 AssignmentRepository outbox / consumer not established | action=REASSIGNED,oldCourierId(UUID),newCourierId(UUID),actorSubject all required; extra properties forbidden | Same distinction between schema fixture and real producer evidence. |
| delivery-cancelled.schema.json / delivery.cancelled | S3 AssignmentRepository outbox / consumer not established | action=CANCELLED,oldCourierId(UUID),actorSubject required; optional nullable newCourierId; extra properties forbidden | Same suites; no inferred new consumer. |
| delivery-failed.schema.json / delivery.failed | Declaration only; no matching runtime publisher/consumer found | Envelope-only allOf; payload/eventType are not constrained by this schema | **Gap:** does not enforce failed-event semantics or payload privacy. Do not tighten an unpublished/undecided business interface speculatively. S3/S5 owners must agree whether this is active and supply the approved payload. |

Schema source of truth is `contracts/events/*.schema.json`; the table summarizes it and does not replace required/optional field definitions. Parcel schemas reject names/phones/addresses; assignment events intentionally contain actor/courier identifiers. Do not describe all events as having no identifiers or remove approved identifiers in the name of privacy. Event ACL/TLS configuration, broker delivery and current runtime acceptance need a disposable environment.

## Pending interface decisions

All rows below are **PENDING APPROVAL**, with no new production adapters implemented. Issue #351 authorizes inventory and tests; it does not approve these new contracts.

Dependency #350 was read on 2026-11-12: GitHub reports it closed, with no issue comments. That state alone does not approve new shared interfaces. Its merged source at this base supplies account checks and Keycloak mapper/runbooks, while `docs/identity/dispatcher-claims-and-credentials.md` still explicitly records dynamic transport as blocked. The merchant provisioning runbook's reference to downstream gRPC checks differs from current S2/S5 REST clients; source and the REST seam inventory above take precedence. No new provisioning RPC or stale-token SLA is inferred from issue closure.

| Needed interface | Exact unresolved contract decisions | Required owners / next action |
|---|---|---|
| Identity/eligibility | Canonical subject versus profile UUID; eligible state; REST versus gRPC; caller authority; missing/disabled status; deadline/error mapping | #350 kirusigan03 with #354 Nilakshana23 and #351 pavi3alnx. Review existing REST DTO and proto differences first. |
| Claim/release | Atomic ownership token, expected version, replay/idempotency, conflict and compensation semantics; method/fields not approved | #353 Jathuja and #354 Nilakshana23; approve versioned provider contract before adapters. |
| Tracking hydration | Allowed customer/merchant/courier references, minimum private fields, authorized lookup versus event enrichment, retention and replay | #353 Jathuja, #357 hephzibah-peries, #350 kirusigan03; no cross-service SQL or fabricated event identities. |
| Reporting | Approved paths, dispatcher permission, aggregate/time semantics and error shape | #358 14Jannan supplies spec; #351 serializes gateway/generator integration. Reserved routes are not implemented endpoints. |

There is deliberately no invented RPC signature for these undecided policies. A concrete versioned proposal requires domain owner decisions; this register records the exact unanswered fields/semantics rather than claiming an approved minimum interface.
