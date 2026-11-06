-- Historical hashes used two-decimal weight normalization. Never reinterpret them as v2.
-- Default 1 also preserves inserts from the existing no-key/merchant workflows.
ALTER TABLE parcel.parcel
    ADD COLUMN fingerprint_version SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_parcel_fingerprint_version CHECK (fingerprint_version IN (1, 2));
