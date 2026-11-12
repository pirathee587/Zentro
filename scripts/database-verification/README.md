# Five database recovery drill

This procedure is for disposable, nonproduction PostgreSQL databases only. It
does not deploy services or alter application migrations. Each service owner
continues to own its Flyway migration tree.

Provide one connection URL per database through the environment variables
`S1_DB_URL` through `S5_DB_URL`. URLs must point to five distinct databases.
The script uses `pg_dump`/`pg_restore` and requires PostgreSQL client tools on
`PATH`. Use a dedicated restore target for every source; never restore over a
live database.

Run `scripts/database-verification/backup-restore.ps1 -BackupDirectory <path>
-RestoreTargets <s1>,<s2>,<s3>,<s4>,<s5>` from the repository root. The script
refuses missing URLs, duplicate source URLs, and existing backup files. Store
the resulting dump files and command output in access-controlled evidence
storage; do not commit database data or credentials. Run
`verify-restore.sql` against each restored database; it reports constraint
validation, indexes and continuity-table presence. Compare continuity row
counts captured before and after restore using the service-specific table
names. Do not treat table-presence output as row-count equality.

For each service, record source/restore row counts for representative business
tables, Flyway schema history, foreign-key and unique-constraint checks, and
outbox/dedup continuity where those tables exist. Record elapsed time and
recovery gaps. Validate cross-database denial with the service's normal
least-privilege role. This generic drill does not assert table names because
each service owns its schema.
