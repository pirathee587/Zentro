-- Nullable diagnostics for rolling upgrades and legacy jobs.
ALTER TABLE dispatch.proof_of_delivery
    ADD COLUMN correlation_id VARCHAR(128),
    ADD COLUMN traceparent VARCHAR(256),
    ADD COLUMN tracestate VARCHAR(512);
