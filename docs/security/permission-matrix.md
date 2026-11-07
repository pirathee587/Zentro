# ZD-041 permission matrix audit (#116)

**Status: Todo / UNVERIFIED.** This audit is evidence from the checked-in source and tests. It is not approval of the permission model; leave the issue open until reviewer @14Jannan approves.

**Serious findings:** none found in the audited REST endpoints. Two proven policy/routing gaps were fixed in this branch. Cross-user profile and delivery ownership checks are present in code and existing tests. No ADMIN role or Admin portal was added; administrative actions remain DISPATCHER-only.

## Scope and evidence limits

Inspected WARP.md, `docs/api/*`, gateway routes/security, controllers and service security for S1 identity, S2 parcel/pricing, S3 dispatch/courier, S4 tracking/reporting, S5 notification/webhooks, the shared JWT starter, realm export, Keycloak scripts, and existing security/ownership tests. Issue #4 was read from GitHub and confirms authentication is already implemented and contract baselining is its responsibility. GitHub CLI auth failed, so issue #25 could not be fetched through the API; public page retrieval also failed. The requested `infra/keycloak/backups/zentro-realm-export.json` is absent; the available `infra/keycloak/realm-export.json` was inspected instead. No live Keycloak configuration was available.

Role shorthand in the inventory: C=CUSTOMER, M=MERCHANT, CO=COURIER, D=DISPATCHER, S=SERVICE, `anon`=unauthenticated, `roleless`=valid token with no roles. `—` means denied. These are accepted role claims, not exclusive user types; tokens may carry several roles. Public tracking intentionally permits anonymous and human reads, while rejecting a SERVICE-only token.

## Endpoint inventory and matrix

The role columns show **current behavior after this branch's fixes**. The expected column is the least-privilege policy derived from WARP.md and the task. “Gateway → service” names both enforcement layers. For the `/api/v1/parcels` base path, method-specific rules apply; unmatched methods under that prefix are denied at the gateway.

| Method + path | Service | Gateway → service enforcement | Ownership / scope | Current roles | Expected roles |
|---|---|---|---|---|---|
| GET `/api/v1/users/me` | S1 | human → any human | JWT `sub` | C,M,CO,D | C,M,CO,D |
| PATCH `/api/v1/users/me` | S1 | human → any human | JWT `sub`; mutable profile fields only | C,M,CO,D | C,M,CO,D |
| GET `/api/v1/users/{userId}` | S1 | human → any human + owner-or-D check | caller's own ID; D may read another user | C,M,CO,D (D cross-user) | same |
| PATCH `/api/v1/users/{userId}` | S1 | human → any human + owner-or-D check | caller's own ID; D may update another user | C,M,CO,D (D cross-user) | same |
| GET `/api/v1/users/courier-profile` | S1 | human → CO | JWT `sub` via courier profile service | CO | CO |
| POST `/api/v1/users/courier-profile/provision` | S1 | human → CO | JWT `sub`; provisions only caller | CO | CO |
| GET `/api/v1/courier/profile` | S1 | CO → CO | JWT `sub` via courier profile service | CO | CO |
| POST `/api/v1/courier/profile/provision` | S1 | CO → CO | JWT `sub`; provisions only caller | CO | CO |
| GET `/api/v1/merchants/me` | S1 | human → any human | JWT `sub`; caller's application only | C,M,CO,D | C,M,CO,D |
| POST `/api/v1/merchants` | S1 | human → C | JWT `sub`; request cannot choose applicant | C* | C* |
| GET `/api/v1/merchants/api-key` | S1 | human → M | JWT `sub`; own merchant key | M | M |
| POST `/api/v1/merchants/api-key` | S1 | human → M | JWT `sub`; approved and active account required | M (approved/active) | M (approved/active) |
| DELETE `/api/v1/merchants/api-key` | S1 | human → M | JWT `sub`; own merchant key | M | M |
| GET `/api/v1/admin/merchants` | S1 | D → D | Dispatcher review queue; optional `?status=` filter | D | D |
| PATCH `/api/v1/admin/merchants/{applicationId}` | S1 | D → D | application ID; D reviews (APPROVED/REJECTED/SUSPENDED from PENDING) | D | D |
| PATCH `/api/v1/admin/merchants/{applicationId}/suspend` | S1 | D → D | suspend APPROVED merchant; suspension reason required | D | D |
| PATCH `/api/v1/admin/merchants/{applicationId}/reinstate` | S1 | D → D | reinstate SUSPENDED merchant back to APPROVED | D | D |
| GET `/api/v1/admin/merchants/{applicationId}/api-key` | S1 | D → D | application ID; D reads key metadata | D | D |
| DELETE `/api/v1/admin/merchants/{applicationId}/api-key` | S1 | D → D | application ID; D revokes key | D | D |
| GET `/api/v1/admin/users` | S1 | D → D | platform user list | D | D |
| GET `/api/v1/admin/users/{userId}` | S1 | D → D | user ID; D user management | D | D |
| PATCH `/api/v1/admin/users/{userId}` | S1 | D → D | user ID; D user management | D | D |
| GET `/api/v1/admin/couriers` | S1 | D → D | platform courier list | D | D |
| GET `/api/v1/admin/couriers/{courierId}` | S1 | D → D | courier ID; D management | D | D |
| PATCH `/api/v1/admin/couriers/{courierId}` | S1 | D → D | courier ID; D management | D | D |
| POST `/api/v1/parcels` | S2 | C → C | customer subject comes from JWT | C | C |
| POST `/api/v1/parcels/estimate`, `/api/v1/parcels/estimate-fee` | S2 | C,D → C,D | Stateless quotation; no user resource | C,D | C,D |
| PATCH `/api/v1/parcels/{parcelId}/status` | S2 | D → D; S allowed only on direct internal service call | parcel ID; actor comes from token; SERVICE lifecycle is restricted in S2 | D via gateway; D,S direct | D via gateway; D,S direct |
| GET `/api/v1/zones` | S2 | any human → C,M,CO,D | Shared active zone list | C,M,CO,D; ADMIN now denied | C,M,CO,D |
| GET `/api/v1/admin/pricing-rules` | S2 | D → D | Shared active pricing rule | D | D |
| PUT `/api/v1/admin/pricing-rules` | S2 | D → D | Shared pricing configuration | D | D |
| POST `/api/v1/merchant/orders` | S2 | M → M + approved/active check | merchant ID derived from JWT `sub`; PENDING, REJECTED, SUSPENDED return 403 | M (approved/active) | M (approved/active) |
| GET `/api/v1/merchant/orders` | S2 | M → M + approved/active check | list filtered by JWT `sub`; PENDING, REJECTED, SUSPENDED return 403 | M (approved/active) | M (approved/active) |
| GET `/api/v1/merchant/orders/{id}` | S2 | M → M + approved/active check | lookup constrained by JWT `sub`; other merchant's order is 404; SUSPENDED returns 403 | M (approved/active, own order) | same |
| GET `/api/v1/dispatch/queue`, `/api/v1/dispatch/unassigned` | S3 | D → D | platform dispatch queue | D | D |
| GET `/api/v1/dispatch/eligible-couriers?parcelId=…` | S3 | D → D | operational data | D | D |
| GET `/api/v1/dispatch/workload` | S3 | D → D | platform workload | D | D |
| POST `/api/v1/dispatch/assignments` | S3 | D → D | assignment controlled by dispatcher | D | D |
| GET `/api/v1/courier/deliveries` | S3 | CO → CO | assigned deliveries only, looked up via authenticated courier profile | CO | CO |
| GET `/api/v1/courier/deliveries/{parcelId}` | S3 | CO → CO | assignment courier ID and user ID must match caller | CO (own assignment) | same |
| GET `/api/v1/courier/deliveries/{parcelId}/proof` | S3 | CO → CO | assignment checked before proof lookup | CO (own assignment) | same |
| POST `/api/v1/courier/deliveries/{parcelId}/proof` | S3 | CO → CO | assignment checked before evidence write; S3 calls S2 as SERVICE | CO (own assignment) | same |
| GET `/api/v1/tracking/mine` | S4 | C,M → C,M | filter uses JWT subject; customer/merchant cannot supply another owner | C,M (own records) | C,M (own records) |
| GET `/api/v1/tracking/{trackingRef}` | S4 | public except S-only → public except S-only | Public tracking reference; response omits names, contact details, full addresses, fees | anon,C,M,CO,D,roleless | same; S-only denied |
| GET `/api/v1/tracking/{trackingRef}/events` | S4 | public except S-only → public except S-only | Public tracking reference; public event data | anon,C,M,CO,D,roleless | same; S-only denied |
| GET `/api/v1/tracking/{trackingRef}/stream` | S4 | public except S-only → public except S-only | Public tracking reference; public event stream | anon,C,M,CO,D,roleless | same; S-only denied |
| GET `/api/v1/merchant/webhooks` | S5 | M → M | merchant ID is JWT `sub` | M | M |
| POST `/api/v1/merchant/webhooks` | S5 | M → M | merchant ID is JWT `sub`; approved/active check | M (approved/active) | M (approved/active) |
| DELETE `/api/v1/merchant/webhooks` | S5 | M → M | merchant ID is JWT `sub` | M | M |
| POST `/api/v1/merchant/webhooks/secret` | S5 | M → M | merchant ID is JWT `sub`; approved/active check | M (approved/active) | M (approved/active) |
| GET `/api/v1/merchant/webhooks/deliveries` | S5 | M → M | merchant ID is JWT `sub`; deliveries scoped to merchant | M | M |
| GET `/api/v1/admin/notification-attempts` | S5 | D → D | Operational integration history | D | D |
| GET `/api/v1/admin/webhook-deliveries` | S5 | D → D | Operational integration history | D | D |

`*` Effective claims can include CUSTOMER alongside another role because CUSTOMER is a Keycloak default role. Whether a COURIER or DISPATCHER who also has CUSTOMER may book a personal parcel or submit a merchant application is an owner decision; the endpoint checks the CUSTOMER claim and then scopes the resulting resource to the token subject.

Gateway-configured `/api/v1/admin/zones/**` and `/api/v1/admin/audit-logs/**` prefixes have no matching controller endpoint in the audited services. They return an upstream 404 rather than exposing an unguarded controller. They are not counted as endpoints. No S4 report controller exists despite the gateway's reserved `/api/v1/reports/**` route.

## Findings

| ID | Classification | Evidence / reproduction | Status |
|---|---|---|---|
| F1 | PROVEN GAP | S2 `ZoneController` accepted `ROLE_ADMIN`; the realm export has no ADMIN role and gateway role conversion filters it. Direct service test with a valid ADMIN-only JWT returned success before the fix. | Fixed: removed ADMIN from the controller rule; direct signed-token test now expects 403. |
| F2 | PROVEN GAP | Gateway's identity route omitted `/api/v1/courier/profile/**`, while the path is a controller mapping in S1. The earlier `/api/v1/courier/**` S3 route captured it, so it did not reach its owner. | Fixed: explicit S1 route predicate and gateway route-forwarding test. |
| F3 | PROVEN GAP | Gateway previously allowed DISPATCHER to POST `/api/v1/parcels` although S2 only permits CUSTOMER, and allowed CUSTOMER to PATCH parcel status although S2 only permits DISPATCHER or SERVICE. | Fixed: gateway now has method-specific booking, quotation, and status rules and denies unmatched parcel methods; gateway tests assert allowed and forbidden roles and forwarding. |
| F4 | PROVEN GAP | S4 allowed `SERVICE`-only JWTs to access public tracking directly, while gateway rejected them. | Fixed: S4 retains anonymous/public access but denies SERVICE-only claims; real signed-JWT test asserts 403. |
| D1 | RISK / NEEDS OWNER DECISION | `CUSTOMER` is a default realm role; Keycloak-created users and seeded COURIER/DISPATCHER accounts consequently hold it alongside their operational role. Customer-only booking/application checks therefore also permit those multi-role users. | Follow-up decision; no role behavior changed. Proposed issue text below. |
| D2 | RISK / NEEDS OWNER DECISION | Merchant approval in S1 changes application state but does not grant a Keycloak MERCHANT role. No application endpoint performs privileged role assignment. The checked-in realm export also has only the public `zentro-web` client and no client-credentials SERVICE client, although S3 requires provisioned SERVICE credentials for completion. Actual runtime provisioning may exist outside this repository. | Follow-up configuration/ownership decision; no role or client was added. Proposed issue text below. |
| OK1 | OK | S1 and S2 use token `sub` for caller identity; user profile non-owner access is denied, while DISPATCHER has intended management access. | Existing ownership and authorization tests. |
| OK2 | OK | Merchant order and webhook handlers derive merchant identity from token `sub`; order lookups constrain by merchant and webhook rows are merchant-scoped. | Existing service and API tests. |
| OK3 | OK | Courier delivery/proof operations resolve the courier from the token and compare both courier ID and user ID against the assignment before reading or writing. | Existing courier workflow/security tests. |
| OK4 | OK | Human and admin endpoints enforce roles in the owning service as well as at the gateway. The shared service filter chain authenticates all requests by default. | Existing zero-trust tests across S1-S5. |

## Role assignment and JWT claim path

- The checked-in realm export has default role `CUSTOMER`. The seed script adds `DISPATCHER` to the seeded dispatcher and `COURIER` to seeded couriers; it does not remove the default CUSTOMER role.
- Self-registration is enabled in Keycloak. The realm export's only configured default is CUSTOMER; it contains no mapping that grants MERCHANT, COURIER, DISPATCHER, SERVICE, or ADMIN to self-registrants. Application code does not expose a self-service privileged role assignment endpoint.
- Merchant application submission is available to CUSTOMER; approval is a dispatcher-only S1 mutation. Approval records database status but does not grant the Keycloak MERCHANT realm role. S2/S5 separately require both MERCHANT claim and approved/active status.
- No code path in the checked-in S1 REST API assigns human privileged realm roles. The intended process for granting MERCHANT/COURIER/DISPATCHER claims needs confirmation; no self-registration privilege escalation is proven by the checked-in realm definition.
- SERVICE is defined as a realm role. S3's `ServiceTokenProvider` uses configured client credentials and fails closed when credentials are empty. The realm export does not define that confidential client or a SERVICE role mapping. This indicates external provisioning is expected but does not prove live credentials are absent.
- Gateway `RoleConverter` and the shared service `KeycloakRealmRoleConverter` both read `realm_access.roles` and produce `ROLE_*` authorities. Gateway restricts recognized roles to CUSTOMER, MERCHANT, COURIER, DISPATCHER, SERVICE; service converters accept realm claims generally, with endpoint checks providing authorization. Gateway and service therefore consume the same role claim path.
- The available realm export has no composites, client roles, or client scopes. The `zentro-web` client maps the `zentro-api` audience and realm roles to the token claim `realm_access.roles`; it is public, has no service account, and uses standard authorization code/PKCE. No secret values were printed or copied.
- No separate ADMIN role is used or permitted by this audit. Administrative operations are DISPATCHER-only.

## Follow-up issue text (ready to paste; GitHub CLI auth unavailable)

### Follow-up D1

**Title:** Confirm CUSTOMER default-role overlap for courier and dispatcher actions

**Owner:** @pirathee587 (backend repository CODEOWNER; confirm product owner)

**Gap:** New Keycloak users receive CUSTOMER by default. Seeded COURIER and DISPATCHER users retain CUSTOMER in addition to their operational role, so they pass CUSTOMER-only parcel booking and merchant application submission checks.

**Reproduction:** Inspect `infra/keycloak/realm-export.json` (`defaultRoles: [CUSTOMER]`) and `infra/keycloak/seed-users.sh` role grants; issue a token carrying COURIER+CUSTOMER or DISPATCHER+CUSTOMER and call `POST /api/v1/parcels` or `POST /api/v1/merchants` through the gateway.

**Proposed next action:** Product/identity owner decides whether operational accounts may also act as customers. If prohibited, adjust only account role provisioning and add role-combination regression tests; preserve CUSTOMER as the default for new customer registrations.

### Follow-up D2

**Title:** Document and verify privileged role and SERVICE client provisioning

**Owner:** @pirathee587 (backend repository CODEOWNER), coordinating the deployment/Keycloak owner

**Gap:** Merchant approval updates S1's application state but does not assign the MERCHANT realm role. The checked-in realm export also omits the confidential client credentials client used by S3 for SERVICE calls. Runtime/manual provisioning may exist outside the repository and was not available for verification.

**Reproduction:** Review `MerchantApplicationService.review`, `infra/keycloak/realm-export.json`, and `ServiceTokenProvider`; no S1 role grant or exported SERVICE client configuration is present. Without valid `POD_CLIENT_ID`/`POD_CLIENT_SECRET`, S3 completion fails closed as `SERVICE_AUTH_NOT_CONFIGURED`.

**Proposed next action:** Keycloak/deployment owner documents the approved MERCHANT grant and SERVICE client provisioning process, then verifies role scope, audience, and least privilege in a disposable realm. Do not add an ADMIN role or expose the client secret in repository files.

## Reproduction commands and actual test results

```bash
cd backend
mvn --batch-mode test package
```

**Environment:**
- Java: `openjdk version "21.0.12.1" 2026-08-18`
- Maven: `Apache Maven 3.8.7`
- Docker: `Docker version 29.8.0, build 88096ef`
- OS: `Linux 7.0.0-38-generic`

**Actual Reactor Results:**
- Reactor Summary: `BUILD SUCCESS [ 05:35 min ]` (all 8 modules packaged)
- Total tests executed across reactor: `1346 run, 0 failures, 0 errors, 15 skipped` (summed from the generated Surefire XML reports)
- Breakdown by module:
  - `api-gateway`: 37 unit/route tests passed; 146 integration tests, 1 skipped (`@Disabled` known problem detail shape mismatch in upstream 5xx sanitizer)
  - `identity-partner-service`: 175 tests passed (14 skipped in `CourierPostgresTestcontainersIntegrationTest` due to Testcontainers 1.20.4 API version 1.32 vs Docker 29.8.0 min API version 1.40 requirement; test aborted cleanly via `assumeTrue(isDockerAvailable())`)
  - `parcel-pricing-service`: 547 tests run, 0 failures, 0 skipped with Docker running
  - `dispatch-courier-service`: 144 tests run, 0 failures, 0 skipped
  - `tracking-reporting-service`: 31 tests run, 0 failures, 0 skipped with Docker running
  - `notification-integration-service`: 259 tests run, 0 failures, 0 skipped

The CI failure supplied with this branch showed a webhook retry firing before its logged `nextTry`. Retry timestamps were generated from the application clock, while due rows were queried against PostgreSQL `now()`, so clock skew could make a future retry appear due. Both notification and webhook retry schedulers now use the same injected application clock as the timestamp writers.

Focused regression cases:
- `GatewayBehaviorTests.parcelEndpointsEnforceTheirMethodSpecificRolesAtTheGateway`
- `GatewayBehaviorTests.everyConfiguredPrefixForwardsUnmodifiedToItsConfiguredService`
- `ZeroTrustJwtSecurityTest.rejectsAdminRoleOnZones`
- `ZeroTrustJwtSecurityTest.serviceOnlyTokenIsRejectedFromPublicTracking`

## Evidence table for #116

| Acceptance criterion from the supplied task | Proof |
|---|---|
| Inspect current role mappings, gateway and service enforcement | Endpoint inventory and role/claim notes above; source paths and tests cited. |
| Map endpoints to current and least-privilege permissions | Endpoint and role matrix above. |
| Check both gateway and service authorization | Gateway → service column and F1-F4. |
| Verify user/resource ownership from token subject | Ownership column and OK1-OK3; existing owner-scoping tests. |
| Inspect default/privileged role assignment and SERVICE model | Role assignment section; D1-D2 follow-ups. |
| Fix only proven gaps with negative tests | F1-F4 fixes and listed tests. |
| No ADMIN role, authentication rebuild, gateway bypass, or contract change | No role/contract changes; internal service-to-service calls (S2 status update) and JWT setup retained. |
| Run service and full backend tests with environment/results | Per-module & reactor build success verified: 1346 run, 0 failures, 15 skipped. |
| Obtain independent @14Jannan review; keep issue Todo / UNVERIFIED | Review is pending; issue remains Todo / UNVERIFIED until @14Jannan approves. |
| Record exact merged commit SHA | To be recorded after merge; no SHA invented. |

| Evidence item | Value |
|---|---|
| Merged commit SHA | To be recorded after merge |

## Notes on Inter-Service Calls & Future Routes

1. **Internal Service-to-Service Calls (Gateway Bypass):**
   `PATCH /api/v1/parcels/{id}/status` permits `ROLE_DISPATCHER` through the API Gateway, and allows both `DISPATCHER` and `SERVICE` at S2 directly. The gateway intentionally rejects `SERVICE`-only tokens on public routes; internal microservices (specifically S3 completing deliveries) invoke S2 directly on internal port 8082 using client-credentials `SERVICE` tokens as per architecture specification.
2. **Future Endpoints:**
   `docs/api/parcel-booking.md` references a future `Location: /api/v1/parcels/{id}` endpoint. When implemented in its respective task, an explicit gateway rule must be added under `/api/v1/parcels/{id}`.

## Not audited

Live Keycloak runtime instances, secret store material, database credentials, frontend route components, and GraphQL/gRPC surfaces outside the protected REST endpoints. Internal service-to-service direct calls that bypass the gateway (such as S3 calling S2 status update with SERVICE token) were verified in code but not routed via perimeter gateway.
