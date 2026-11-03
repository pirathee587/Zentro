ALTER TABLE parcel.parcel
    ADD COLUMN merchant_id VARCHAR(255),
    ADD COLUMN external_order_ref VARCHAR(100);

ALTER TABLE parcel.parcel
    ADD CONSTRAINT uq_parcel_merchant_external_ref UNIQUE (merchant_id, external_order_ref);

ALTER TABLE parcel.parcel
    ADD CONSTRAINT ck_parcel_merchant_external_ref_pair CHECK (
        (merchant_id IS NULL AND external_order_ref IS NULL)
        OR (merchant_id IS NOT NULL AND external_order_ref IS NOT NULL)
    );

-- Merchant orders use request_hash for external-reference replay protection instead of
-- the customer booking idempotency_key. Preserve the booking constraint while allowing
-- the corresponding merchant order pair to carry its request hash.
ALTER TABLE parcel.parcel
    DROP CONSTRAINT ck_parcel_idempotency_hash,
    ADD CONSTRAINT ck_parcel_idempotency_hash CHECK (
        (idempotency_key IS NULL AND request_hash IS NULL)
        OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL)
        OR (merchant_id IS NOT NULL AND external_order_ref IS NOT NULL AND request_hash IS NOT NULL)
    );

CREATE INDEX idx_parcel_merchant_created ON parcel.parcel (merchant_id, created_at DESC)
    WHERE merchant_id IS NOT NULL;
