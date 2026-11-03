-- Version number for each parcel row. It increases by one on every accepted status change,
-- so concurrent or stale updates can be detected.
ALTER TABLE parcel.parcel
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
