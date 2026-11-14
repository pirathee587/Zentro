# Clean backend runtime evidence — G39 / Issue #359

Status: PARTIAL. Compose structural validity and actual process readiness are
different checks. [Runbook](../operations/backend-runbook.md) owns procedures;
[candidate index](backend-integration-evidence.md) owns execution results.

Root Compose does not provision all required external dependencies. Existing
`DisposableGatewayStack` provides PostgreSQL, Kafka, Keycloak and service launch
support. `BackendAcceptanceStack` extends it with S3/S4, five separate database
logins/schemas, restricted SERVICE completion credentials and enabled outbox /
consumer flags. Main 10 shared support is reused, not a production deployment.

Readiness gates:

1. Docker reachable and fixture ports 18080–18085 free.
2. Clean PostgreSQL/Kafka/Keycloak containers; imported approved realm template.
3. Five service databases created with separate runtime logins; Flyway migrations.
4. Signed fixture-role tokens have exactly their intended application authority.
5. Six actual packaged JVMs start from E2E `target`; no personal service `.env`.
6. Each `/actuator/health` returns 200; this alone is not business readiness.
7. Real gateway booking and event facts reconcile against owning databases.
8. Dependency fault returns its existing response, rolls back, and retries recover.
9. Real S4 restart retains the actually consumed event count.
10. Quiesced owning-service dumps restore into new disposable databases; selected
    counts reconcile. Full integrity, broker restart and RPO/RTO require Main 03 review.

Historical corrected fixture run `311ac2b` passed gates 1–6 and selected restore
checks, but initial tracking hydration failed and prevented its embedded restart
assertions. The current suite moves restart evidence into an independently
executable test without weakening the failing public-read assertion.

Environment was rechecked on 2026-11-14: Linux amd64, Java 21.0.12.1, Maven 3.8.7,
Docker 29.8.0. Root and Keycloak Compose `config --quiet` returned 0. Exact current
candidate run, counts, limitations and expected/actual statuses are linked from the
index. No image build, Docker health result or historical CI run is substituted
for a real smoke test or independent recovery sign-off.
