# Disposable Courier integration verification

Run from the repository root with the existing local Maven cache and Docker Desktop:

```powershell
mvn --offline --batch-mode --no-transfer-progress -f backend/pom.xml -pl merchant-order-webhook-e2e -am -Pe2e '-Dtest=CourierDashboardFlowIT' '-Dzentro.e2e.courierDashboard=true' '-Dzentro.e2e.containerNetwork=true' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dapi.version=1.44' verify
```

The container-network flag is opt-in. The existing loopback-JVM scenario remains available without it.

## Isolation and configuration

The harness creates a new Docker network, disposable PostgreSQL databases with separate service logins, Kafka and Keycloak. It does not join the existing Zentro networks, import local `.env` files or use shared Supabase. Keycloak administrative operations target only the fixture's random mapped port.

S1, S2, S3, S4 and the gateway use newly packaged working-tree JARs copied into new containers using the existing local service images as Java runtime bases. Existing images are not retagged and existing containers are not replaced. The runtime images must already exist locally. Testcontainers assigns fixture host ports; there is no replacement of host ports 8080–8084 or 8180.

All isolated services use:

```properties
JWT_ISSUER_URI=http://localhost:8180/realms/zentro
JWT_JWKS_URI=http://zentro-keycloak:8080/realms/zentro/protocol/openid-connect/certs
JWT_AUDIENCE=zentro-api
```

The isolated Keycloak fixture uses `KC_HOSTNAME=http://localhost:8180` to preserve the issuer claim, while its administrative and token requests use its own random mapped port. No requests are sent to the developer's Keycloak at host port 8180.

S4 uses:

```properties
ZENTRO_DASHBOARD_DISPATCH_BASE_URL=http://dispatch-courier-service:8083
KAFKA_CONSUMER_ENABLED=true
```

S3 uses `IDENTITY_BASE_URL=http://identity-partner-service:8081` and `PARCEL_BASE_URL=http://parcel-pricing-service:8082`. S2 uses `ZENTRO_ASSIGNMENT_DISPATCH_BASE_URL=http://dispatch-courier-service:8083` and **fixture-only** eligibility `PICKED_UP`. This is not a decision about production eligibility.

S2 V10 and S3 V5 run through Flyway only in the disposable owning databases. A gateway-to-Keycloak public-certificate request checks the exact internal DNS/port configuration. Real authenticated HTTP calls then exercise S4 → S3 → S1 and ownership boundaries.

## Read behavior and limitations

The scenario executes the frontend's actual GraphQL query and compares identity row snapshots before and after dashboard reads. REST assignments and proof reads are tested with owning and foreign couriers and unauthenticated/non-courier callers.

**The existing Courier Profile GET is not read-only.** It calls the existing application-user upsert and profile provisioning logic. The test expects an identity row-version change and reports it explicitly; this must not be interpreted as a write-safety pass. The test does not change that established API behavior.

Credentials and bearer tokens remain in fixture memory. HTTP evidence contains only synthetic business responses and is written under `target/courier-dashboard-http-evidence.md`. Do not add token-endpoint responses or Authorization headers to that evidence.

The harness stops only its own containers/network after completion. It does not leave a browser-ready stack running. Browser acceptance needs an isolated browser connection and interactive fixture accounts; the API fixtures are courier-role client-credentials accounts, not an interactive login test.

This command is verification only. It does not authorize changes to existing environment files, shared databases, Keycloak, or a production deployment.
