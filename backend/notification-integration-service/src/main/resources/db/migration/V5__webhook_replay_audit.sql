CREATE TABLE notification.webhook_delivery_replay (
    delivery_id UUID PRIMARY KEY REFERENCES notification.webhook_delivery (id),
    dispatcher_subject VARCHAR(255) NOT NULL,
    replayed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
