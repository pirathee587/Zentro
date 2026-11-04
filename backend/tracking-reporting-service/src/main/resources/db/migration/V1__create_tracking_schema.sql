CREATE SCHEMA IF NOT EXISTS tracking;

CREATE TABLE tracking.tracking_parcel (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parcel_id UUID,
    tracking_ref VARCHAR(32) NOT NULL,
    customer_subject VARCHAR(255) NOT NULL,
    merchant_subject VARCHAR(255),
    sender_name VARCHAR(100) NOT NULL,
    sender_phone VARCHAR(20) NOT NULL,
    sender_address VARCHAR(255) NOT NULL,
    sender_city VARCHAR(100),
    recipient_name VARCHAR(100) NOT NULL,
    recipient_phone VARCHAR(20) NOT NULL,
    recipient_address VARCHAR(255) NOT NULL,
    recipient_city VARCHAR(100),
    weight_kg NUMERIC(6, 2) NOT NULL DEFAULT 1.0,
    size VARCHAR(16),
    category VARCHAR(40) NOT NULL DEFAULT 'Standard',
    description VARCHAR(255),
    destination_type VARCHAR(32),
    is_express BOOLEAN NOT NULL DEFAULT false,
    fee NUMERIC(12, 2) NOT NULL DEFAULT 0.0,
    currency VARCHAR(3) NOT NULL DEFAULT 'LKR',
    estimated_days INTEGER NOT NULL DEFAULT 3,
    status VARCHAR(32) NOT NULL DEFAULT 'CREATED',
    failure_reason VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tracking_parcel_ref UNIQUE (tracking_ref),
    CONSTRAINT ck_tracking_parcel_status CHECK (status IN (
        'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED'))
);

CREATE INDEX idx_tracking_parcel_customer ON tracking.tracking_parcel (customer_subject, created_at DESC);
CREATE INDEX idx_tracking_parcel_status ON tracking.tracking_parcel (status);

CREATE TABLE tracking.tracking_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID,
    tracking_ref VARCHAR(32) NOT NULL,
    parcel_id UUID,
    status VARCHAR(32) NOT NULL,
    previous_status VARCHAR(32),
    actor_role VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    title VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    failure_reason VARCHAR(200),
    location VARCHAR(100),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_tracking_event_status CHECK (status IN (
        'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED'))
);

CREATE INDEX idx_tracking_event_ref_time ON tracking.tracking_event (tracking_ref, occurred_at ASC);
CREATE INDEX idx_tracking_event_parcel_time ON tracking.tracking_event (parcel_id, occurred_at ASC);

CREATE TABLE tracking.processed_event (
    consumer_name VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);

