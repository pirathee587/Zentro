# Local Keycloak on the shared Supabase identity database: switch runbook

**Status: prepared, not executed.** Every step that writes to shared Supabase, or switches the running
Keycloak, needs the owner's final approval. Nothing here deletes the local `keycloak_keycloak-data` volume.

## Target

The Keycloak application runs locally in Docker (`zentro-keycloak`, `start-dev`, port 8180, issuer
`http://localhost:8180/realms/zentro`, unchanged). Its database is the **existing shared Supabase schema
`keycloak`** (project in ap-southeast-2, session pooler 5432, TLS `sslmode=require`), the same as
commit `09da765` (2026-10-23). Commit `6ae623d` (2026-11-04) dropped that configuration.

Change: `keycloak-shared-db.patch` (this folder). It passes only `KC_DB`, `KC_DB_URL`, `KC_DB_USERNAME`,
`KC_DB_PASSWORD`, `KC_DB_SCHEMA` and `KC_DB_POOL_MAX_SIZE` from `infra/keycloak/.env`, removes
`--import-realm`, and keeps the local volume mounted for rollback. Services and frontend are unchanged.

## Rule: one Keycloak per shared database at a time

`start-dev` keeps realm, user, authorization and key caches in each process's memory, with no cross-process
invalidation. Two laptops running Keycloak against this database at the same time would:
- serve stale realm, client and role settings after an admin change made on the other instance;
- serve stale user data (disabled users, role changes, password resets, new Google links) until a restart;
- keep inconsistent brute-force counters and sign-in state per process;
- risk lost updates when both edit the same realm or user.

Keycloak supports several instances on one database only as a **cluster** (`start` with the `ispn`
cache and JDBC_PING discovery, with nodes reachable on port 7800). Developer laptops on different networks
can't form one. Supported choices:

1. **One designated Keycloak at a time** (recommended now). Only one machine runs Keycloak on the shared
   database. Everyone else stops their Keycloak, or keeps theirs on a private local database for
   experiments and never uses it for real accounts.
2. **One shared Keycloak endpoint** that every developer points to (a hosted server, or the designated
   machine reached over a VPN such as Tailscale). Issuer and frontend URLs change.
3. **Real cluster** over a private network (VPN mesh): `start` mode, `ispn` cache, JDBC_PING, port 7800
   reachable between nodes. Complex for laptops.

Not supported, and not proposed: several independent `start-dev` instances on one database, even with
caches disabled.

## What must happen together with the switch (each a separate approval)

Switching the database alone does **not** lose data, but without these steps people lose access:

| Effect if switched alone | Fix (shared write) |
|---|---|
| S2–S5 service-to-service calls fail (401), because the 4 service clients don't exist in the shared realm | Import the 4 clients with their existing secrets, **then re-add each client's `SERVICE` realm scope mapping** (partial import drops it; verified in the sandbox) |
| Logout from `http://localhost` is rejected (post-logout URI not allowed) | Add `http://localhost/*` to `zentro-web` redirect and post-logout URIs |
| The 2 local `pa***` couriers and the `pi***@gmail` customer can't sign in | Partial-import them with their **original IDs and password hashes** (verified in the sandbox) |
| courier1 signs in as shared ID `a90809c2`, whose S1 profile is empty, so its 2 assignments disappear from view | S1 core reconciliation re-points profile `7582b1a1` (needs its own approval) |
| The dispatcher signs in as shared ID `e6609c60`, with an empty S1 profile | Same core reconciliation (`5189303c`) |
| Forgot password is off in shared, and shared SMTP is unverified | Enable password reset; verify or replace SMTP with the working app password |

## Order (on approval)

1. **Gate:** confirm with teammates that no other Keycloak uses the shared database. Check
   `pg_stat_activity` and that `root_auth_session` shows no fresh rows.
2. **Fresh backups:** read-only `pg_dump` of `keycloak` and S1 `identity`; local volume archive and
   offline `kc.sh export`, each restore-tested in a throwaway Postgres or Keycloak.
3. **Stop** `zentro-keycloak`. Apply `keycloak-shared-db.patch`. Start Keycloak (expect "no schema
   changes", since both are 26.8.1). Check discovery and that the issuer is unchanged.
4. Apply the shared-realm fixes above, through the admin API.
5. Run the S1 core reconciliation (`10_s1_reconcile_core.sql` from the private backup folder), if approved.
6. Recreate nothing else; services keep the same issuer. Run the browser checks: every role, refresh,
   logout, account switching, courier1 assignments, customer parcels, Google linking.

Expected sign-in interruption: about 10–20 minutes.

## Rollback

1. Stop `zentro-keycloak`, revert the patch (`git apply -R`) and start it. It resumes on the untouched
   local volume, with the same issuer.
2. Undo the S1 changes with the generated rollback SQL. It was verified byte-exact in isolation.
3. **Writes made after the switch:** users created, linked or changed in the shared realm afterwards
   don't exist locally. Before rolling back, export them (admin API or `kc.sh export` of the shared
   database) so they can be re-created if needed. S1 rows created after the switch are left in place;
   the rollback SQL only touches the reconciled rows.
4. Shared-realm additions (clients, redirect URIs, imported users) are harmless to leave. If required,
   they can be removed individually by ID from the saved export.
