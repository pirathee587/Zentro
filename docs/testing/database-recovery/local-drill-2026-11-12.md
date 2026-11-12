# Local recovery drill evidence — 2026-11-12

Environment: disposable PostgreSQL 18 container on loopback; no production data
or credentials used. The repository `backup-restore.ps1` completed for five
source/restore database pairs. Post-restore reconciliation compared every
user-table row count between each source and restore; all five matched. Each
restore had zero unvalidated constraints. The recorded FK/unique constraint
counts were S1 3/53, S2 1/53, S3 1/49, S4 0/49, S5 2/51. These are structural
counts from this run, not an assertion that every service must have a specific
number of constraints. PostgreSQL restricted a temporary role granted CONNECT
to S1 from connecting to S2, while permitting the S1 connection.

Migration qualification: S2–S5 committed migration sets applied to fresh
databases. S1 applied through V7 but V8 failed: its unqualified
`DROP INDEX IF EXISTS merchant_application_status_created_idx` did not find the
index in the `identity` schema, then the unqualified `CREATE INDEX` failed
because the schema-qualified index created earlier already exists. The S1
backup/restore comparison therefore covers the successfully applied prefix
through V7 only, not the complete S1 schema. The applied migration was not
changed under the ownership rule.

Disposable database containers and dump files were stopped/removed after the
drill. This does not measure RPO/RTO under load, interrupted migration recovery,
service-specific business-row invariants, or production database grants.
