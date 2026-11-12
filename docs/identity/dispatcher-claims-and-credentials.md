# Dispatcher Claims and Internal Completion Credentials (G09 / Issue #204)

## 1. Context and Problem Statement

Downstream service S3 (`dispatch-courier-service`) enforces granular permissions on dispatch endpoints via `DispatchPermissions.java` (`@dispatchPermissions.has(authentication, '...')`):
- `GET /api/v1/dispatch/queue` → `DISPATCH_VIEW`
- `GET /api/v1/dispatch/unassigned` → `DISPATCH_VIEW`
- `GET /api/v1/dispatch/workload` → `DISPATCH_VIEW`
- `GET /api/v1/dispatch/eligible-couriers` → `DISPATCH_VIEW`
- `POST /api/v1/dispatch/assignments` → `DISPATCH_ASSIGN`

Prior to this implementation:
1. `infra/keycloak/realm-export.json` had no protocol mapper to map user permissions to the OIDC access token `"permissions"` claim.
2. The Keycloak declarative user-profile component did not define `permissions`, leaving attribute governance unconfigured.
3. Seeding scripts (`seed-users.ps1`, `seed-users.sh`) only assigned `DISPATCHER` realm role to `dispatcher@zentro.test` without assigning permission attributes, resulting in `403 Forbidden` on S3 queue and assignment operations.

---

## 2. Keycloak Protocol Mapping & Attribute Governance

### 2.1 Protocol Mapper Configuration
Under client `zentro-web` in `infra/keycloak/realm-export.json` and `apply-realm-config.ps1`, a protocol mapper `permissions claim` of type `oidc-usermodel-attribute-mapper` is provisioned:
- **User Attribute:** `permissions`
- **Token Claim Name:** `permissions`
- **Claim Type:** `String` (multivalued = `true`)
- **Token Inclusions:** `access.token.claim: true`, `id.token.claim: true`, `userinfo.token.claim: true`

When minted, the access token payload includes:
```json
{
  "sub": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "realm_access": {
    "roles": ["DISPATCHER"]
  },
  "permissions": [
    "DISPATCH_VIEW",
    "DISPATCH_ASSIGN"
  ]
}
```

### 2.2 Privilege Escalation Protection
In `realm-export.json` under `components.org.keycloak.userprofile.UserProfileProvider`:
```json
{
  "name": "permissions",
  "displayName": "Permissions",
  "multivalued": true,
  "permissions": {
    "edit": ["admin"],
    "view": ["admin", "user"]
  }
}
```
Standard end-users (`user`) cannot mutate their own `permissions` attribute via registration or profile update endpoints. Only Keycloak administrators (`admin`) can assign or modify permissions.

---

## 3. Seed Automation

The seeding scripts (`infra/keycloak/seed-users.ps1` and `infra/keycloak/seed-users.sh`) have been updated:
- The default development dispatcher `dispatcher@zentro.test` is seeded with:
  - Role: `DISPATCHER`
  - Permissions: `["DISPATCH_VIEW", "DISPATCH_ASSIGN"]`
- Couriers (`courier1@zentro.test`, `courier2@zentro.test`) are seeded without dispatcher permissions.

---

## 4. Technical SERVICE Role Boundary

- The Keycloak realm defines the `SERVICE` role strictly for machine-to-machine internal service communication.
- As verified across test reports (`TC-SEC-04`):
  - Tokens carrying only `ROLE_SERVICE` are rejected on all human endpoints (Gateway and downstream human controllers).
  - `ROLE_SERVICE` is permitted solely on authorized internal gRPC and status update endpoints.
- No static service credentials or secrets are stored in the repository.

---

## 5. Transport Blocker & Ownership Boundary (G14 Coordination)

- **Policy Blocker:** Dynamic automated runtime cross-service provisioning transport is blocked awaiting formal G14 contract decisions.
- **Contract Boundary:** In compliance with strict task boundaries, no new gRPC provisioning methods or shared proto modifications were introduced. S3 files remain untouched. Keycloak configuration and seed scripts bridge this gap cleanly and securely.
