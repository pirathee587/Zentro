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

CREATE INDEX idx_parcel_merchant_created ON parcel.parcel (merchant_id, created_at DESC)
    WHERE merchant_id IS NOT NULL;
