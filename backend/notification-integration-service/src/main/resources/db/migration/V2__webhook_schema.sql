-- Merchant webhooks (ZD-17, #32).

-- One endpoint per merchant. merchant_id is the merchant account id (Keycloak subject), the same value S2
-- puts in parcel events as payload.merchantId. The signing secret is stored encrypted (AES-GCM), never in
-- plain text; secret_prefix is a short non-secret hint shown to the merchant.
CREATE TABLE notification.webhook_subscription (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id VARCHAR(255) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    secret_ciphertext TEXT NOT NULL,
    secret_prefix VARCHAR(8),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_webhook_subscription_merchant UNIQUE (merchant_id)
);

-- One row per event sent to a subscription, with the outcome of the latest attempt. payload is the event
-- envelope exactly as received (ids, tracking reference, status, merchant id; no names, phone numbers or
-- addresses), so retries send the same signed body. FAILED rows are the dead-letter record.
CREATE TABLE notification.webhook_delivery (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subscription_id UUID NOT NULL REFERENCES notification.webhook_subscription (id),
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    parcel_id UUID NOT NULL,
    payload TEXT NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    response_status INT NULL,
    last_error VARCHAR(500) NULL,
    next_attempt_at TIMESTAMPTZ NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_webhook_delivery_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'RETRY', 'FAILED')),
    CONSTRAINT ck_webhook_delivery_attempts CHECK (attempts >= 0),
    CONSTRAINT uq_webhook_delivery_subscription_event UNIQUE (subscription_id, event_id)
);

CREATE INDEX idx_webhook_delivery_due ON notification.webhook_delivery (status, next_attempt_at);
CREATE INDEX idx_webhook_delivery_subscription ON notification.webhook_delivery (subscription_id, created_at);
-- Per-parcel order: finds an earlier open delivery for the same subscription and parcel.
CREATE INDEX idx_webhook_delivery_parcel ON notification.webhook_delivery (subscription_id, parcel_id, created_at);
