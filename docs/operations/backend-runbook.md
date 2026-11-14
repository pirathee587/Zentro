# Backend setup and operations — Issue #359

Status: PARTIAL. Commands below follow repository source; successful execution and
remaining failures are indexed in [candidate evidence](../testing/backend-integration-evidence.md).
A reviewer walkthrough, restore drill and complete business lifecycle remain required.

## Prerequisites and dependency order

Use Java 21, Maven, Python 3 and reachable Docker with Compose. Observed local
versions are Java 21.0.12.1, Maven 3.8.7 and Docker 29.8.0; this is one tested
environment, not a cross-platform support guarantee. Disposable images currently
come from existing fixture versions: PostgreSQL 16-alpine, Keycloak 26.3.3,
Confluent Kafka 7.8.0. Ports 18080–18085 must be free for acceptance.

Start PostgreSQL and Kafka, initialize five separate databases/logins, start
Keycloak/import realm, provision test clients, then S1/S2/S5 and S3/S4, then
gateway. Flyway owns schema initialization: `identity`, `parcel`/`pricing`,
`dispatch`, `tracking`, `notification`. Never run a service using another
service's database credentials. PostgreSQL container sharing is a disposable
test optimization, not a production topology requirement.

## Configuration and secrets

[Configuration reference](../development/configuration.md), service `.env.example`
files and [production security](../security/production-configuration.md) are the
authoritative variable references. Do not copy actual secrets into evidence.

| Scope | Required inputs / flags |
|---|---|
| Each service | Own `DB_URL`, `DB_USER`, `DB_PASSWORD`; `JWT_ISSUER_URI`, `JWT_JWKS_URI`, `JWT_AUDIENCE`; `SERVER_PORT` |
| Gateway | `S1_URI` through `S5_URI`, JWT inputs; process launcher supplies `SERVER_PORT` |
| S1 | Approved Keycloak realm/provisioning; fixture disables gRPC with `GRPC_SERVER_ENABLED=false` |
| S2 | `ZENTRO_IDENTITY_BASE_URL`, `KAFKA_BOOTSTRAP_SERVERS`, `OUTBOX_PUBLISHER_ENABLED=true` |
| S3 | `IDENTITY_BASE_URL`, `PARCEL_BASE_URL`, `POD_TOKEN_URI`, `POD_CLIENT_ID`, `POD_CLIENT_SECRET`, `POD_WORKER_ENABLED=true`, `OUTBOX_ENABLED=true`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` |
| S4 | `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_CONSUMER_ENABLED=true` |
| S5 | `KAFKA_BOOTSTRAP_SERVERS`, `NOTIFICATIONS_CONSUMER_ENABLED=true`, `WEBHOOKS_ENABLED=true`, `WEBHOOK_SECRET_KEY`; local HTTP webhooks require fixture-only `WEBHOOKS_ALLOW_INSECURE_LOCAL=true` |

Disposable credentials are generated/provisioned by the fixture. Persistent
credentials must come from the deployment custodian's approved secret store;
no production store path or custodian contact has been supplied. Keycloak realm
template: `infra/keycloak/realm-export.json`; production bootstrap credentials
must never use fixture values. Completion client receives SERVICE only, not
DISPATCHER impersonation. No new permission policy is approved by fixture setup.

## Reproducible build, startup and smoke

From repository root, the evidence runner invokes the full clean reactor, packages
service JARs, starts disposable services for acceptance and exports only counts:

```sh
python3 scripts/backend-acceptance/verify.py --output /tmp/zentro-359-evidence.json
```

Equivalent build command, from `backend` (replace the revision with the actual
candidate SHA):

```sh
mvn --batch-mode --no-transfer-progress -Dbuild.revision=<candidate-sha> -DrunE2E clean verify
```

The new shared stack launches real JARs from the E2E `target` directory, avoiding
personal service `.env` files. It imports actual Keycloak realm configuration and
uses signed Keycloak test-client tokens. These service-account test identities
are not proof of human interactive login, account disablement or production
courier provisioning. No assignment/tracking business rows are inserted.
Check HTTP 200 on `/actuator/health` at each loopback port 18080–18085. Business
requests use gateway 18080; direct-service probes are authorization diagnostics.
Existing Kafka listeners/producers create/use their configured topics in the
disposable broker; production partition/retention/ACL provisioning needs Main 03/07
approval. Health alone does not prove event propagation or lifecycle readiness.

For persistent development setup use the existing configuration guide. Root
Compose requires external databases/Kafka and Keycloak network; it is not a
complete bootstrap. Validate without printing expanded secrets:

```sh
docker compose config --quiet
docker compose -f infra/keycloak/docker-compose.keycloak.yml config --quiet
```

## Backup, restore, replay and recovery

Persistent-environment commands require operator-supplied service-scoped
`PGSERVICE` entries in a private libpq service file and `PGPASSFILE` or approved
credential injection. Never put passwords in command lines/logs. For each service:

```sh
pg_dump --format=custom --file=/private/backup/service.pgdump
pg_restore --list /private/backup/service.pgdump
```

Restore to a **new disposable database** with its own `PGSERVICE` selection:

```sh
pg_restore --exit-on-error --single-transaction --no-owner --no-acl /private/backup/service.pgdump
```

These are procedures awaiting a recorded drill, not executed restore evidence.
Record per-service row/event counts, migration version, checksums, restore duration
and recovery-point expectations before approving them. Do not overwrite a live DB.

Outbox and POD workers retain retry state in their owning databases. Restart the
same packaged service against the same disposable DB; compare assignment/proof,
history and outbox counts before/after. Never clear idempotency or processed-event
tables to force replay. Kafka offset reset, topic retention and replay window must
be approved by Main 03/07/08 before use; no generic reset command is safe here.
Use [S5 recovery](s5-notification-recovery.md) for its already implemented retry
and replay boundaries. A broker restart test and backup/restore acceptance remain
BLOCKED pending owner/reviewer evidence; do not claim durability from health.

## Troubleshooting and shutdown

Inspect local `backend/merchant-order-webhook-e2e/target/e2e-*.log` and Surefire
reports without publishing payloads/tokens. Socket permission errors indicate an
execution sandbox restriction; use an authorized Docker/network environment.
Port conflicts must fail before fixture start. A 503
`DISPATCH_SOURCE_UNAVAILABLE` is the current production dependency blocker, not an
empty successful queue. Receipt 403 at gateway with S2 200/404 indicates missing
route approval/registration. Check Kafka flags and actual consumed events before
treating a tracking 404 as an HTTP routing defect.

JUnit teardown stops only its launched service processes and disposable
containers. Use normal test termination; do not prune shared Docker state.
For an operator-owned persistent Compose stack, stop the exact Compose project
without deleting volumes. Rollback uses the previous approved application
artifact/configuration; do not reverse applied Flyway migrations. Restore and
forward-fix decisions belong to the service owner and Main 03.
