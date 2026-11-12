# Database recovery verification

See [the five database drill](../../../scripts/database-verification/README.md).
The current automation creates independent PostgreSQL dumps and restores each
to a caller-supplied disposable target. It validates archive readability and
records elapsed time. It does not yet assert service-specific row counts,
foreign-key/unique invariants, outbox/dedup continuity, interrupted migration
recovery, or cross-database grants. Those checks require schemas and role names
confirmed by each service owner; record them in the drill evidence before
claiming recovery acceptance. Keep evidence and data out of Git.
