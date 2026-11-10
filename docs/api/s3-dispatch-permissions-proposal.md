# S3 dispatcher permissions — pending security contract approval (#133)

Local implementation for owner review only. **Not approved for merge or deployment.**
Approval is required from @pirathee587 and @Deadsecnote1 for the claim, endpoint matrix,
legacy-token behavior and excluded commands. Keycloak provisioning is not changed here.

## Proposed trusted claim

The already validated access token must contain `realm_access.roles` granting
`DISPATCHER` and a top-level JSON array such as:

```json
{"permissions": ["DISPATCH_VIEW", "DISPATCH_ASSIGN"]}
```

S3 reads only its authenticated `JwtAuthenticationToken`, after the existing resource-server
filter validates signature, issuer, expiry and audience. The permission helper does not
validate tokens independently, read request headers/body, or grant roles. Its entry point
is the controller method-security expression. Internal code must not construct authentication
objects from unvalidated data.

Missing/null, non-array, non-string elements, blank or whitespace-padded elements reject
the claim. Permission identifiers match exactly, including case. Unknown nonblank names
grant nothing; duplicates have set semantics. Only the four identifiers below are recognized.
The proposed authority convention is `PERM_<identifier>`, while the primary role remains
`ROLE_DISPATCHER`; this implementation compares JWT strings directly without adding authorities
or changing the shared converter.

## Endpoint matrix

Every listed handler requires **DISPATCHER and its permission**:

| Method and path | Permission |
|---|---|
| GET `/api/v1/dispatch/queue` | `DISPATCH_VIEW` |
| GET `/api/v1/dispatch/unassigned` | `DISPATCH_VIEW` |
| GET `/api/v1/dispatch/workload` | `DISPATCH_VIEW` |
| GET `/api/v1/dispatch/eligible-couriers` | `DISPATCH_VIEW` |
| POST `/api/v1/dispatch/assignments` | `DISPATCH_ASSIGN` |

`DISPATCH_ASSIGN` does not imply view permission. `DISPATCH_REASSIGN` and `COURIER_MANAGE`
are reserved: no S3 reassignment command exists, and courier profile/availability management
remains in S1. No new endpoints are introduced. Courier self-service roles and assignment
ownership checks remain unchanged.

Invalid or absent bearer credentials return 401. Valid authentication without the required
role/permission returns 403 using existing security handlers, before business logic and
downstream calls. Forged permission headers or body fields cannot grant access.

## Compatibility and approval dependencies

Existing dispatcher tokens without this claim receive 403; there is no role-only fallback.
Before deployment, the Keycloak owner must provision protected permission grants and the
access-token mapper, ensure users cannot edit the source attributes, and refresh existing
dispatcher sessions. Confirm propagation through Gateway without replacing the bearer token.
Live provisioning and integrated Gateway/Keycloak behavior have not been verified here.
No S1, Gateway, shared starter, Keycloak, frontend, migration, payload or event-schema changes
are included. Real queue/assignment execution also retains the existing unavailable parcel-source
integration blocker; permission checks do not resolve it.

## Documentation workflow

`docs/api/build_openapi.py` generates the committed OpenAPI files; `--check` verifies
the inventory against controller mappings. Generated artifacts and the shared generator
are intentionally unchanged while this contract awaits approval. After approval, update
the generator's S3 authorization descriptions and regenerate/review the S3 artifact.

## Verification boundaries

`DispatchPermissionsTest` uses authentication fixtures. Controller tests use mocked dependencies.
`ZeroTrustJwtSecurityTest` exercises signed JWT validation against a local JWKS server with
mocked repositories/upstreams. `CourierWorkflowIntegrationTest` uses real S3 HTTP/security and
PostgreSQL, but mocks the decoder, S1 and parcel source. None is real cross-service E2E.

Local verification (Docker 29.9.2, PostgreSQL Testcontainers, Java 21):

```powershell
cd backend
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am "-Dtest=DispatchPermissionsTest,DispatchQueueControllerTest,ZeroTrustJwtSecurityTest,CourierWorkflowIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dapi.version=1.44" test
mvn --batch-mode --no-transfer-progress -pl dispatch-courier-service -am "-Dapi.version=1.44" test package
```

- Focused run: 75 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS.
- Full S3 run: 181 tests, 0 failures, 0 errors, 0 skipped; upstream security starter:
  7 tests, 0 failures, 0 errors, 0 skipped. Maven packaging: BUILD SUCCESS.
- Initial sandbox run could not access Docker and skipped 9 integration cases. It also
  exposed two test-fixture mistakes (Mockito restubbing and JWT subject reset); these were
  corrected before the successful Docker-enabled runs. The initial run is not passing evidence.
- `python docs/api/build_openapi.py --check` fails on an existing S1 inventory mismatch:
  GET/DELETE `/api/v1/admin/merchants/{applicationId}/api-key` and PATCH
  `/api/v1/admin/merchants/{applicationId}/suspend` and `/reinstate` are missing from its spec.
  No S3 mapping mismatch was reported. S1 artifacts were left untouched.
- `git diff --check` passes.
