-- Events written in the same transaction as the parcel change they describe.
-- A separate publisher reads unpublished rows later; nothing here talks to a broker.
CREATE TABLE parcel.outbox_event (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NULL,
    attempts INT NOT NULL DEFAULT 0,
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_outbox_unpublished ON parcel.outbox_event (occurred_at)
    WHERE published_at IS NULL;
