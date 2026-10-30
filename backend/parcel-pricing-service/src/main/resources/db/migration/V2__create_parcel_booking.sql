CREATE SCHEMA IF NOT EXISTS parcel;

CREATE TABLE parcel.parcel (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tracking_ref VARCHAR(16) NOT NULL,
    customer_subject VARCHAR(255) NOT NULL,
    sender_name VARCHAR(100) NOT NULL,
    sender_phone VARCHAR(20) NOT NULL,
    sender_address VARCHAR(255) NOT NULL,
    sender_city VARCHAR(100),
    recipient_name VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(20) NOT NULL,
    recipient_address VARCHAR(255) NOT NULL,
    recipient_city VARCHAR(100),
    weight_kg NUMERIC(6, 2) NOT NULL,
    size VARCHAR(16),
    category VARCHAR(40) NOT NULL,
    description VARCHAR(255),
    destination_type VARCHAR(32),
    is_express BOOLEAN NOT NULL DEFAULT false,
    notes VARCHAR(500),
    fee NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    estimated_days INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    idempotency_key VARCHAR(100),
    request_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_parcel_tracking_ref UNIQUE (tracking_ref),
    CONSTRAINT uq_parcel_customer_idempotency UNIQUE (customer_subject, idempotency_key),
    CONSTRAINT ck_parcel_status CHECK (status IN (
        'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_parcel_weight CHECK (weight_kg > 0),
    CONSTRAINT ck_parcel_fee CHECK (fee >= 0),
    CONSTRAINT ck_parcel_idempotency_hash CHECK (
        (idempotency_key IS NULL AND request_hash IS NULL)
        OR (idempotency_key IS NOT NULL AND request_hash IS NOT NULL))
);

CREATE INDEX idx_parcel_customer_created ON parcel.parcel (customer_subject, created_at DESC);
