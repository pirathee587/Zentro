-- Nullable diagnostics for rolling upgrades and legacy jobs.
ALTER TABLE notification.notification_attempt
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN traceparent VARCHAR(256),
    ADD COLUMN tracestate VARCHAR(512);
ALTER TABLE notification.webhook_delivery
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN traceparent VARCHAR(256),
    ADD COLUMN tracestate VARCHAR(512);
