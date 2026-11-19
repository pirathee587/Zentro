# Keycloak service-client provisioning runbook (shared realm)

**Status: prepared, not executed.** Running it creates four clients in the shared
`zentro` realm, whose Keycloak database is shared Supabase. Each step needs the
approvals listed at the end. No credentials appear in this document.

Tool: `infra/keycloak/provision_service_clients.py` (create-only by default).
Clients (from `infra/keycloak/service-clients.json`):

| Client | Service | Secret variable | Extra claim |
|---|---|---|---|
| `zentro-parcel-service` | S2 | `ZENTRO_PARCEL_SERVICE_CLIENT_SECRET` | none |
| `zentro-dispatch-service` | S3 | `ZENTRO_DISPATCH_SERVICE_CLIENT_SECRET` | `pod_permissions=pod:read pod:update` |
| `zentro-tracking-service` | S4 | `ZENTRO_TRACKING_SERVICE_CLIENT_SECRET` | none |
| `zentro-notification-service` | S5 | `ZENTRO_NOTIFICATION_SERVICE_CLIENT_SECRET` | none |

Each client is confidential with service accounts only (no browser, implicit or password
login), `fullScopeAllowed=false`, audience `zentro-api`, SERVICE as its only effective
realm role (the realm default-roles composite, which carries CUSTOMER, is removed), no
realm-management roles, and the attribute `zentro.managed-by=provision_service_clients`.

## Tool modes

| Command | Effect |
|---|---|
| `--plan` | Read-only (writes refused in code). Shows CREATE / EXISTS / drift |
| *(no flags)* | Create-only. Refuses before any write if any target client exists |
| `--verify` | Read-only. Checks tokens and effective roles with the injected secrets |
| `--repair-dispatch zentro-dispatch-service --dry-run` | Read-only check of the documented partial Dispatch state |
| `--repair-dispatch zentro-dispatch-service` | Client-scoped Dispatch role/mapper repair; requires separate owner approval |
| `--repair-tracking zentro-tracking-service --dry-run` | Read-only check of the documented partial Tracking creation state |
| `--repair-tracking zentro-tracking-service` | Client-scoped Tracking role repair; requires separate owner approval |
| `--create-missing zentro-tracking-service --create-missing zentro-notification-service --dry-run` | Read-only validation before creating the two missing clients |
| `--create-missing zentro-tracking-service --create-missing zentro-notification-service` | Creates only those two missing clients after Parcel/Dispatch verify cleanly; requires separate owner approval |
| `--create-notification --dry-run` | Read-only validation before creating Notification alone |
| `--create-notification` | Creates only Notification after Parcel, Dispatch, and Tracking verify cleanly; requires separate owner approval |
| `--update-existing` | Reconciles managed clients' settings. Never sends a secret |
| `--rotate-secret ID` | Only way to change a secret; named, managed clients only |
| `--remove-managed ID --confirm-remove` | Recovery. Manifest clients tagged as managed only |

## Shell setup (Git Bash, repository root)

Credentials are loaded into the environment only, never echoed:

```bash
set -a
. <(grep -E '^KC_BOOTSTRAP_ADMIN_(USERNAME|PASSWORD)=' infra/keycloak/.env | tr -d '\r')
. <(grep -E '^ZENTRO_[A-Z]+_SERVICE_CLIENT_SECRET=' .env | tr -d '\r')   # after step 3
set +a
export KC_URL=http://localhost:8180
PROV="python -B infra/keycloak/provision_service_clients.py"
```

## 1. Pre-provisioning snapshot (read-only)

1. `$PROV --plan` must show **would CREATE** for all four. Stop on EXISTS (UNMANAGED):
   someone created a client by hand; resolve it with them first.
2. Save a read-only realm snapshot (client list, roles, default-role composites; no
   secrets) to `C:\Users\hp\AppData\Local\ZentroBackups\keycloak-pre-provision-<UTC>\`.

## 2. Teammate coordination

- Name one operator. Nobody else runs the provisioner, `--rotate-secret` or `--update-existing`.
- Agree how the four secrets are shared (a private channel or password manager, never chat or git).
- Teammates running their own Keycloak against the shared database may need to restart it
  to see the new clients.
- Using the app afterwards causes real service-to-service writes on shared data
  (for example S3 → S2 parcel status on delivery completion).

## 3. Generate secrets (owner-approved)

Create a root `.env` (git-ignored) with four 64-character random values:

```bash
test -e .env && { echo ".env exists; merge manually"; exit 1; }
python -c "import secrets
for n in ('PARCEL','DISPATCH','TRACKING','NOTIFICATION'):
    print(f'ZENTRO_{n}_SERVICE_CLIENT_SECRET={secrets.token_urlsafe(48)}')" > .env
git check-ignore -q .env && echo ignored
```

Restrict the file to your Windows account. Print only "set/empty", never values.

## 4. Create-only provisioning

`$PROV` (no flags). Expected: four lines `token verified (SERVICE only, aud=zentro-api, ...)`, exit 0.

## 5. Token validation

- `$PROV --verify` must show four `token verified` lines.
- `$PROV --plan` must show `EXISTS (managed); no drift` for all four.

## 6. Failure handling

| Situation | Action |
|---|---|
| Refused before any write (existing client, missing secret, unmanaged client) | Nothing changed. Fix the cause; rerun `--plan` |
| Partial failure | The tool lists clients created in this run. Run `--plan`. Do not rerun create-only (it refuses) |
| Token or role verification fails | Keep services stopped from using the client. Collect the `--plan` drift output and escalate |
| A secret leaks | `--rotate-secret <id>` with a new value, update `.env`, recreate that service, tell the team |

### Narrow partial-provisioning recovery

These modes are limited to the known partial state. They must not be run against the
shared realm during offline development or testing.

For a partially created Tracking client, the recovery mode accepts only the managed
client with the expected confidential service-account settings, direct
`default-roles-zentro` mapping, inherited CUSTOMER composite, no client realm-scope
roles, and only the expected audience mapper. Preview it with:

`python -B infra/keycloak/provision_service_clients.py --repair-tracking zentro-tracking-service --dry-run`

After separate owner approval, run the same command without `--dry-run`. It removes
only Tracking's direct default-role mapping, assigns SERVICE to its service account
and client scope, and preserves the existing audience mapper. It does not create
Notification or modify Parcel, Dispatch, or realm-wide roles.

When Parcel, Dispatch, and Tracking are healthy and only Notification is missing,
preview Notification-only creation with:

`python -B infra/keycloak/provision_service_clients.py --create-notification --dry-run`

This mode verifies the three preserved clients' configuration, role mappings, mapper
sets, and client-credentials tokens before reporting that Notification would be
created. After separate owner approval, run the same command without `--dry-run`; it
creates no other client and does not update existing clients.

1. Preview Dispatch only:
   `python -B infra/keycloak/provision_service_clients.py --repair-dispatch zentro-dispatch-service --dry-run`
2. After separate owner approval, repair Dispatch:
   `python -B infra/keycloak/provision_service_clients.py --repair-dispatch zentro-dispatch-service`
   It removes only the dispatch service account's direct `default-roles-zentro` mapping,
   assigns `SERVICE` to that account and its client scope, and configures its audience and
   POD permission access-token mappers. It refuses unmanaged clients or state outside the
   documented partial/intermediate recovery state.
3. Preview Tracking and Notification creation:
   `python -B infra/keycloak/provision_service_clients.py --create-missing zentro-tracking-service --create-missing zentro-notification-service --dry-run`
4. After separate owner approval, create those two clients with the same command without
   `--dry-run`. The command refuses if either already exists and verifies existing Parcel
   and Dispatch clients before its first write.
5. On partial failure, stop and inspect with `--plan`. Neither recovery mode deletes or
   rolls back clients automatically. Do not rerun create-only or use unrestricted
   `--update-existing` as recovery.

## 7. Rollback (needs explicit approval)

Only for clients created by this run:

```bash
$PROV --remove-managed zentro-parcel-service --remove-managed zentro-dispatch-service \
      --remove-managed zentro-tracking-service --remove-managed zentro-notification-service --confirm-remove
```

The tool refuses clients outside the manifest or not tagged as managed. `zentro-web`
and every other client are never touched. Their service-account users are deleted with them.

## 8. S2–S5 restart plan (after step 5 passes)

1. `docker compose -f docker-compose.yml up -d --no-deps --no-build parcel-pricing-service tracking-reporting-service notification-integration-service`
2. **S3 is not started by Compose.** Compose alone leaves `POD_WORKER_ENABLED` at its
   default (on). Recreate S3 from `zentro-dispatch-courier-service:flyway-safe` with the
   Compose-resolved environment plus these overrides, exactly as before:

   | Variable | Value |
   |---|---|
   | `SPRING_FLYWAY_ENABLED` | `false` |
   | `SPRING_FLYWAY_VALIDATE_ON_MIGRATE` | `true` |
   | `POD_WORKER_ENABLED` | `false` |
   | `OUTBOX_ENABLED` | `false` |

   Keep the preserved container `zentro-dispatch-courier-service-pre-flyway-safety` stopped.
3. Take a read-only snapshot of shared S3's Flyway history before and after; it must be unchanged.
4. Check each service logs Flyway "up to date" (no migrations), and that the secret is set (presence only).

## 9. Post-restart validation

- The customer dashboard loads; S4 no longer logs "Service OAuth2 client credentials are not configured".
- Courier deliveries and dispatcher workload return 200 instead of 502.
- No `PERMISSION_DENIED` in S1, S2 or S3 logs.
- Assignment coordination still needs V7 (separate approval).

## Approvals required

1. Create the four clients in the shared realm (step 4).
2. Generate the secrets, store them in the root `.env`, and agree how teammates receive them.
3. Name the single operator and confirm teammates are informed.
4. Accept service-to-service writes on shared data once enabled.
5. Recreate S2, S4, S5 and S3 (step 8), with S3 keeping all four overrides.
6. Pre-approve `--remove-managed` for this run's clients if validation fails.
