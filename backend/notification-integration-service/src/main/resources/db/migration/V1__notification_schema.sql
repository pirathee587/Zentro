CREATE SCHEMA IF NOT EXISTS notification;

-- Events this service has already handled, per consumer. The primary key makes duplicate deliveries
-- (at-least-once Kafka, redelivery after a crash, concurrent consumers) a no-op.
CREATE TABLE notification.processed_event (
    consumer VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- One row per notification decision for an event: sent, skipped, waiting for a retry or failed.
-- Holds the tracking reference and status only; no names, phone numbers or addresses.
CREATE TABLE notification.notification_attempt (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    parcel_id UUID NOT NULL,
    tracking_ref VARCHAR(20) NOT NULL,
    parcel_status VARCHAR(32) NOT NULL,
    channel VARCHAR(20) NOT NULL DEFAULT 'MOCK_SMS',
    message VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_notification_attempt_status CHECK (status IN ('PENDING', 'SENT', 'RETRY', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_notification_attempt_parcel_status CHECK (parcel_status IN (
        'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_notification_attempt_attempts CHECK (attempts >= 0),
    CONSTRAINT uq_notification_attempt_event_channel UNIQUE (event_id, channel)
);

CREATE INDEX idx_notification_attempt_due ON notification.notification_attempt (status, next_attempt_at);
CREATE INDEX idx_notification_attempt_tracking ON notification.notification_attempt (tracking_ref, created_at);

-- Messages that could not be parsed or failed validation. Only the reason is stored, never the raw payload.
CREATE TABLE notification.rejected_event (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic VARCHAR(200),
    partition_no INT,
    offset_no BIGINT,
    reason VARCHAR(200),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
