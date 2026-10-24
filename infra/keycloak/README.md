# Local Keycloak and Supabase setup

This standalone Keycloak service uses the Identity Partner Supabase Postgres project and stores Keycloak's own tables in the `keycloak` schema. It does not put a database URL, password, or Supabase key in the browser frontend.

## Start Keycloak

1. Copy `.env.example` to `.env`. A local `.env` has been created for this workspace with a generated bootstrap admin password. `KC_DB_PASSWORD` is intentionally blank; enter the Supabase session-pooler database password in that field on your machine.
2. From the repository root, run `./infra/keycloak/init-schema.ps1` in PowerShell, or `bash infra/keycloak/init-schema.sh` in Git Bash/WSL. Both use the Docker `postgres:16` client to create the schema once. The Supabase session pooler must use port 5432.
3. Start Keycloak with `docker compose -f infra/keycloak/docker-compose.keycloak.yml up -d`.
4. Check `http://localhost:9000/health/ready` (Keycloak exposes health on its management port 9000), then run `./infra/keycloak/seed-users.ps1` in PowerShell or `bash infra/keycloak/seed-users.sh` in Git Bash/WSL. The script creates/updates the dispatcher and courier accounts, assigns their realm roles, and prints fresh passwords once in a table. It saves them to the ignored `infra/keycloak/seed-credentials.local.txt`; another run resets those passwords.

The Compose file mounts the realm import and the Zentro login theme with development theme caching disabled. Keycloak imports a realm only on first creation; changing `realm-export.json` later does not overwrite an existing realm automatically. Apply safe settings, role, client-mapper, and user-profile updates in place with `./infra/keycloak/apply-realm-config.ps1`. It keeps existing users. Deleting the realm and importing again also applies the file but deletes every account and role assignment in that realm.

The generated local admin password is stored only in `.env`. Keep that file private and do not commit it. PowerShell equivalents are provided for Windows users; the shell scripts require `curl`, `jq`, and `openssl` in Git Bash or WSL.

## Google sign-in (OIDC identity brokering)

Configure Google as an identity provider in Keycloak; the React app never receives a Google client secret:

1. In Google Cloud Console, create an OAuth client for a web application. Add `http://localhost:8180/realms/zentro/broker/google/endpoint` as an authorized redirect URI. Add the corresponding public Keycloak realm broker endpoint for each deployed environment.
2. Put the OAuth client ID and secret in the local `infra/keycloak/.env` as `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` (these are OAuth app credentials, not your Gmail password). Run `node infra/keycloak/apply-google-provider.mjs` from the repository root. It creates or updates the enabled Keycloak provider with alias `google`, keeps the secret server-side, makes name/phone fields optional, and disables the first-login profile review. Alternatively, enter the same values in the Keycloak Admin Console under **Identity providers → Google** and set **Authentication → Flows → First Broker Login → Review Profile → Update Profile On First Login** to **Off**.
3. Keep first broker login confirmation enabled for existing-account linking. Do not map Google profile data or email to privileged realm roles. The realm default role is `CUSTOMER`; assign `MERCHANT`, `COURIER`, or `DISPATCHER` only through an authorized Keycloak administrator workflow.
4. The public customer and merchant sign-in screens send `kc_idp_hint=google`. Keycloak must have an enabled provider with the `google` alias for that button to redirect to Google; without it, Keycloak shows its password form. Staff sign-in remains on the normal Keycloak form.

Google credentials are configured in the Keycloak Admin Console and are not stored in this repository. The frontend and backend rely on Keycloak's signed realm roles for authorization. Account enablement is enforced by Keycloak: disable a user in the realm to prevent new sessions/tokens. Self-registration and Google brokered registration receive only the realm default `CUSTOMER` role. After approving a merchant application, a dispatcher must grant `MERCHANT` in Keycloak; pending or rejected users do not receive that role and cannot access merchant APIs. Staff roles are assigned only to provisioned accounts.

S1 uses the same Supabase Postgres database with its own `identity` schema. The new Flyway migration creates application profiles keyed by the immutable Keycloak subject and merchant application/status records. It does not alter Keycloak's `keycloak` schema or Supabase's empty `public` schema. S1 applies the migration when it starts; do not run it manually against the shared database.

## Later main Compose handoff (M7)

M7 can add a `keycloak` service to the main `docker-compose.yml` with image `quay.io/keycloak/keycloak:26.8.1`, `env_file: infra/keycloak/.env`, `8180:8180` and `9000:9000`, the realm/theme mounts from this file, the theme-cache environment values, and command `start-dev --import-realm`. Keep the mounts read-only. Keycloak uses the Supabase session pooler directly; it should not depend on a local Postgres container. Keep this standalone file available for identity development.

## Application data

S1 creates an application user row on the first `GET /api/v1/users/me`. `POST /api/v1/merchants` creates or resubmits a merchant application as `PENDING`; `GET /api/v1/merchants/me` returns its status. Dispatchers review pending applications through `/api/v1/admin/merchants` and `PATCH /api/v1/admin/merchants/{applicationId}`. Do not write directly into S1 tables. If multiple teammates connect separate Keycloak servers to this same Supabase schema, they will overwrite each other's identity data, so use one shared Keycloak instance.
