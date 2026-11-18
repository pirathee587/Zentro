# Distributed POD E2E

The `merchant-order-webhook-e2e` module launches the real service jars as separate local processes and creates disposable PostgreSQL, Kafka, and Keycloak Testcontainers. It reserves loopback ports 18080–18085 before starting and fails early if another process is listening there. The fixture does not start, stop, or reconfigure the project Compose stack.

Package the service jars before running the fixture because it launches executable jars from each service's `target/` directory:

```sh
mvn -f backend/pom.xml -pl merchant-order-webhook-e2e -am -DskipTests package
mvn -f backend/pom.xml -pl merchant-order-webhook-e2e -am -DrunE2E test
```

To run only the distributed POD scenario after packaging:

```sh
mvn -f backend/pom.xml -pl merchant-order-webhook-e2e -am \
  -Dtest=DistributedPodWorkflowIT -Dsurefire.failIfNoSpecifiedTests=false -DrunE2E test
```

The fixture invokes `infra/keycloak/provision_service_clients.py` twice against its disposable realm. The scenario verifies the four client identities, then exercises customer booking, courier profile provisioning, dispatcher assignment, rejection for another provisioned courier, wrong-assignment rejection, courier acceptance and status progression, duplicate proof submission, service-token gRPC completion, and S4/S5 Kafka projections. It stops S2 during a second proof completion, verifies S3 stores a pending retry for `PARCEL_SERVICE_UNAVAILABLE`, restarts S2, and checks eventual `DELIVERED`/`COMPLETED` state.

The fixture fetches the live disposable realm's JWKS once and serves that public-key snapshot over loopback to its local service processes. Customer, dispatcher, and service tokens are still issued by the disposable Keycloak, and each process validates their signatures, issuer, audience, expiry, and permissions. This avoids Testcontainers published-port latency during cold decoder initialization; production Compose continues to use Keycloak's internal JWKS URL.

The gRPC interceptor unit tests cover missing/invalid/expired token rejection, wrong client IDs, forbidden RPC methods, and missing POD permissions. The shared decoder validates issuer, audience, signature, and expiry for accepted tokens. Kafka duplicate and retry semantics are also covered in the S4/S5 consumer and recovery suites. The disposable E2E does not mutate the locally running Keycloak realm or use production service secrets.
