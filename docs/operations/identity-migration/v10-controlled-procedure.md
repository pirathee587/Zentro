# S1 V10 unique email: controlled procedure

**Status: prepared, not executed.** Running any step against shared Supabase needs the owner's final
execution approval. The SQL is in the private backup folder
`%LOCALAPPDATA%\ZentroBackups\identity-migration-prep-20261120T131029Z\scripts\` (it contains
real identifiers, so it is not committed).

## Why a controlled procedure

S1 (identity-partner-service) runs Flyway at startup against the shared S1 database, with no override
today. A new file in `src/main/resources/db/migration` would therefore apply on the next S1 rebuild,
with no review. While duplicate emails exist it would fail and stop S1 from starting.

1. Apply `s1-flyway-off.patch` (this folder) and recreate S1 with `--no-deps`. This sets
   `SPRING_FLYWAY_ENABLED=false` and `SPRING_FLYWAY_VALIDATE_ON_MIGRATE=true` for the S1 container,
   the same arrangement S3 uses. Testcontainers tests still enable Flyway and migrate their throwaway
   databases.
2. Only after that, add `V10__unique_app_user_email_ci.sql` to S1's migration folder (so tests and
   fresh environments get it) and apply it to shared S1 with the guarded steps below.

## Index

```sql
CREATE UNIQUE INDEX IF NOT EXISTS app_user_email_lower_key
  ON identity.app_user (lower(email)) WHERE email IS NOT NULL;
```

- Case-insensitive, matching S1's `LOWER(email)` lookups and the first-sign-in guard (`ACCOUNT_IDENTITY_CONFLICT`).
- Null emails are allowed: service and system identities without email are not blocked.
- Audited data: 0 emails with surrounding whitespace, 0 null emails, 26 rows. Registration paths already
  trim and lowercase the email before inserting it.
- Plain `CREATE INDEX` (not `CONCURRENTLY`), so it runs inside Flyway's transaction. With about 20 rows the
  lock lasts milliseconds.
- The existing application guard stays. The index is the last line of defence against races. In isolated
  tests, 6 concurrent inserts of one new email created exactly 1 row.

## Steps (each needs approval; stop on the first failure)

| # | Step | Expected result | On failure |
|---|------|-----------------|------------|
| 0 | Confirm the S1 container runs with `SPRING_FLYWAY_ENABLED=false` (inspect env) | flag present | stop; do not continue |
| 1 | Fresh read-only `pg_dump --schema=identity` (session pooler 5432) and restore test into a throwaway Postgres | restore exit 0, 26 rows (or the post-cleanup count) | stop |
| 2 | Pre-check: `SELECT lower(email), count(*) FROM identity.app_user WHERE email IS NOT NULL GROUP BY 1 HAVING count(*) > 1;` | **0 rows** (the reconciliation must have run) | stop; reconcile first |
| 3 | Pre-check: `SELECT max(version::int) FROM identity.flyway_schema_history WHERE version IS NOT NULL;` | `9` | stop; investigate drift |
| 4 | Apply with Flyway CLI 11 (same major as the service), pointed at **a folder containing V1–V10 only**, `-validateOnMigrate=true -target=10 -cleanDisabled=true -baselineOnMigrate=false`, session pooler 5432 | `Successfully applied 1 migration … v10` | Flyway rolls the transaction back; history unchanged; re-run 2–3 |
| 5 | Post-check: index exists (`pg_indexes`), `flyway_schema_history` has V10 `success = true`, case-variant duplicate insert fails with `23505` inside a rolled-back transaction | all true | rollback step |
| 6 | Add the identical V10 file to `backend/identity-partner-service/src/main/resources/db/migration/` and run S1's focused tests | checksum identical to the applied file; tests pass | remove the file; S1 has Flyway off, so runtime is unaffected |

## Rollback

`V10__unique_app_user_email_ci_ROLLBACK.sql`: `DROP INDEX IF EXISTS identity.app_user_email_lower_key;`,
then delete the `version = '10'` row from `identity.flyway_schema_history` only when V10 must be fully
undone. No data changes, so rollback is instant and lossless.

## Isolated verification already done (2026-11-20)

Done on a throwaway copy of S1 restored from a fresh dump:
- reconciliation, then V10, then all rollbacks, left the data byte-identical to the original;
- a case-variant duplicate was rejected;
- concurrent duplicate inserts produced exactly one row.
