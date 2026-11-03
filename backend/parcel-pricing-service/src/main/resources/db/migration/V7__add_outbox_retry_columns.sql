-- Retry bookkeeping for the outbox publisher: when to try a failed event again and why it last failed.
-- last_error holds a short error description only, never the event payload.
ALTER TABLE parcel.outbox_event
    ADD COLUMN next_attempt_at TIMESTAMPTZ NULL,
    ADD COLUMN last_error VARCHAR(500) NULL;

-- Lets the publisher check quickly whether a parcel still has an earlier unpublished event,
-- so events of one parcel are always sent in order.
CREATE INDEX idx_outbox_unpublished_aggregate ON parcel.outbox_event (aggregate_id, occurred_at)
    WHERE published_at IS NULL;
