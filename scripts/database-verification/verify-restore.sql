-- Run against each disposable restored service database after migrations complete.
-- Checks that PostgreSQL reports no unvalidated constraints and emits source-owned
-- continuity counts for outbox/dedup/replay records. Row counts must be compared
-- with the corresponding pre-backup evidence; this script cannot infer equality.
SELECT current_database() AS database_name,
       count(*) FILTER (WHERE NOT convalidated) AS unvalidated_constraints
FROM pg_constraint;

SELECT schemaname, tablename, indexname
FROM pg_indexes
WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
ORDER BY schemaname, tablename, indexname;

SELECT continuity_table, to_regclass(continuity_table) AS resolved_table
FROM (VALUES ('parcel.outbox_event'), ('tracking.processed_event'),
             ('notification.processed_event'), ('notification.webhook_delivery_replay'))
     AS expected(continuity_table);
