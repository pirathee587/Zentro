# S3 (dispatch-courier-service) Flyway safety

Application-side Flyway is **disabled by default** for S3. The shared S3 Supabase
database records a historical V5 (`assignment acceptance and status`, checksum
1530139319). The canonical `db/migration` V5 is `assignment_coordination` (checksum
1731076080). Shared S3 must never be migrated from the canonical set, and never
by application startup. Its migrations run only through a separately authorized,
owner-held runbook using a dedicated historical migration artifact (V1–V4, the
original acceptance V5, V6 and later additive migrations; manifest SHA-256
`0ccd38366a29a41d8d27aeb0888c57320f97dc8484546f9e5ee78b47b24882e8`).

## Where it is configured

| Layer | Setting | Effect |
|---|---|---|
| `src/main/resources/application.yml` | `spring.flyway.enabled: false`, `validate-on-migrate: true` | Off for every launch path (IDE, `java -jar`, Helm, Docker) unless explicitly enabled |
| `docker-compose.yml` (dispatch-courier-service `environment:`) | `SPRING_FLYWAY_ENABLED: "false"`, `SPRING_FLYWAY_VALIDATE_ON_MIGRATE: "true"` | Overrides any `env_file` value, including the `.env`'s `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false` |
| `docker-compose.local-db.yml` (S3) | `SPRING_FLYWAY_ENABLED: "true"` | Re-enabled only when S3 points at the isolated `local-postgres` |
| `infra/helm/zentro/values.yaml` + `templates/services.yaml` | `services.dispatch-courier-service.env` sets `SPRING_FLYWAY_ENABLED=false`, `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true`; the template renders per-service `env:` | Kubernetes `env` overrides the `dispatch-courier-service-runtime` Secret (`envFrom`) |
| `backend/dispatch-courier-service/.env.example` | `SPRING_FLYWAY_ENABLED=false`, `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true` | New local `.env` files start safe |
| `src/test/resources/application.properties` | `spring.flyway.enabled=true` | Tests build disposable Testcontainers schemas |
| e2e `DisposableGatewayStack` / `CourierLocalIntegrationStack` | Enable S3 Flyway only after asserting `DB_URL` is the disposable Testcontainers database | Acceptance ITs keep migrating their throwaway databases |

Other services' Flyway configuration is unchanged. `backend/docker-compose.yml`
defines no S3 service. CI (`.github/workflows/ci.yml`) runs only Testcontainers tests
and packaging with no database secrets. `scripts/` only builds images and checks
readiness, and `infra/k8s` holds placeholders only.

## Regression guard

`FlywayDeploymentSafetyTest` (S3 module, runs in `mvn test` and in CI) fails if any of these holds:
- `application.yml` stops defaulting to Flyway off with validation on (checked by binding
  the real `FlywayProperties`), or the `.env` validation bypass alone can enable Flyway.
- The S3 Compose `environment:` in `docker-compose.yml` (or `backend/docker-compose.yml`,
  if S3 is ever added there) no longer forces `ENABLED=false` / `VALIDATE=true`, or no
  longer overrides a worst-case `env_file`.
- `docker-compose.local-db.yml` enables Flyway for a database that is not `local-postgres`.
- The Helm S3 `env` is missing or unsafe, or the template stops rendering it after `envFrom`.
- `.env.example` enables Flyway or disables validation.
- The local, ignored S3 `.env` is connected to Supabase and sets `SPRING_FLYWAY_ENABLED=true`
  (checked only when the file exists; values are never printed).
- Any Compose, Helm, k8s, CI workflow, script, Dockerfile, S3 pom or e2e source enables
  Flyway outside the approved disposable contexts, or adds a Flyway migrate command or plugin.

A mutation check (reintroducing each unsafe setting) made the guard fail in every case.

## Local workflows

- **Default stack (`docker compose up`)**: S3 connects to its configured database
  and runs no migrations. The schema must already exist.
- **Isolated local database**: `docker compose -f docker-compose.yml -f docker-compose.local-db.yml up -d`.
  S3 migrates `local-postgres/dispatch_courier_db` from canonical `db/migration` on
  startup with validation on.
- **Tests**: `mvn test` (Testcontainers) is unaffected, with Flyway enabled for tests only.
- **IDE / `java -jar` against a local throwaway database**: set
  `SPRING_FLYWAY_ENABLED=true` explicitly, and only when the database URL is a
  local disposable database. Note that `application.yml` imports
  `./.env[.properties]` from the working directory. Running from the module
  directory therefore picks up the local `.env` `DB_URL`, which may be shared Supabase.
- **Shared S3 Supabase**: never set `SPRING_FLYWAY_ENABLED=true`. Use the runbook's
  guarded runner, which takes its own explicit approval.

## Do not

- Do not combine `SPRING_FLYWAY_ENABLED=true` with `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=false`
  against shared S3. That bypasses the V5 checksum check and applies any pending
  canonical migration (for example a future additive V7) automatically. A
  disposable-database control test reproduced exactly this.
- Do not run `flyway repair` or edit `dispatch.flyway_schema_history`.
- Do not swap V5 files between the canonical and historical lineages.

## Verification (2026-11-19)

A jar built with this configuration plus a proposed additive V7 (canonical V1–V7;
V7 is not part of this change) was started against a disposable restore of the
shared S3 backup (historical V1–V6, no V7):

| Scenario | Result | V7 applied? |
|---|---|---|
| Compose env (enabled=false, validate=true) | Started | No |
| Non-Compose launch with `.env` bypass only (validate=false; yml default off) | Started | No |
| Flyway force-enabled under Compose (enabled=true, validate=true) | Startup failed: V5 checksum mismatch | No |
| Control: old config (enabled=true, validate=false) | Started | **Yes**, the risk this change removes |

With only this change applied to the base revision, the S3 test suite and
`FlywayDeploymentSafetyTest` pass (see the pull request for counts).
