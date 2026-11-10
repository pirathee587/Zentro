-- Diagnostics only; nullable for existing rows and rolling upgrades.
ALTER TABLE parcel.outbox_event
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN traceparent VARCHAR(256),
    ADD COLUMN tracestate VARCHAR(512);
