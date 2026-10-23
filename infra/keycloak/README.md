# Local Keycloak and Supabase setup

This standalone Keycloak service uses the Identity Partner Supabase Postgres project and stores Keycloak's own tables in the `keycloak` schema. It does not put a database URL, password, or Supabase key in the browser frontend.

## Start Keycloak

1. Copy `.env.example` to `.env`. A local `.env` has been created for this workspace with a generated bootstrap admin password. `KC_DB_PASSWORD` is intentionally blank; enter the Supabase session-pooler database password in that field on your machine.
2. From the repository root, run `./infra/keycloak/init-schema.ps1` in PowerShell, or `bash infra/keycloak/init-schema.sh` in Git Bash/WSL. Both use the Docker `postgres:16` client to create the schema once. The Supabase session pooler must use port 5432.
3. Start Keycloak with `docker compose -f infra/keycloak/docker-compose.keycloak.yml up -d`.
4. Check `http://localhost:9000/health/ready` (Keycloak exposes health on its management port 9000), then run `./infra/keycloak/seed-users.ps1` in PowerShell or `bash infra/keycloak/seed-users.sh` in Git Bash/WSL. The script creates/updates the dispatcher and courier accounts, assigns their realm roles, and prints fresh passwords once in a table. It saves them to the ignored `infra/keycloak/seed-credentials.local.txt`; another run resets those passwords.

The Compose file mounts the realm import and the Zentro login theme with development theme caching disabled. Keycloak imports a realm only on first creation; changing `realm-export.json` later does not overwrite an existing realm automatically. Apply safe settings, role, client-mapper, and user-profile updates in place with `./infra/keycloak/apply-realm-config.ps1`. It keeps existing users. Deleting the realm and importing again also applies the file but deletes every account and role assignment in that realm.

The generated local admin password is stored only in `.env`. Keep that file private and do not commit it. PowerShell equivalents are provided for Windows users; the shell scripts require `curl`, `jq`, and `openssl` in Git Bash or WSL.

## Later main Compose handoff (M7)

M7 can add a `keycloak` service to the main `docker-compose.yml` with image `quay.io/keycloak/keycloak:26.8.1`, `env_file: infra/keycloak/.env`, `8180:8180` and `9000:9000`, the realm/theme mounts from this file, the theme-cache environment values, and command `start-dev --import-realm`. Keep the mounts read-only. Keycloak uses the Supabase session pooler directly; it should not depend on a local Postgres container. Keep this standalone file available for identity development.

## Application data handoff

S1 creates an application user row on the first `GET /api/v1/users/me`. Courier profile rows are created through `POST /api/v1/admin/couriers`; do not write directly into S1 tables. If multiple teammates connect separate Keycloak servers to this same Supabase schema, they will overwrite each other's identity data, so use one shared Keycloak instance.
