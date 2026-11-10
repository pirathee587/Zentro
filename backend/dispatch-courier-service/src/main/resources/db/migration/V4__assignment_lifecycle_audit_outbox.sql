CREATE TABLE dispatch.assignment_audit (
    audit_id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    old_courier_profile_id UUID,
    new_courier_profile_id UUID,
    actor_subject VARCHAR(255) NOT NULL CHECK (length(trim(actor_subject)) > 0),
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_assignment_audit_action CHECK (action IN ('ASSIGNED', 'REASSIGNED', 'CANCELLED'))
);

CREATE INDEX ix_assignment_audit_parcel ON dispatch.assignment_audit (parcel_id, occurred_at);

CREATE TABLE dispatch.outbox_event (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    CONSTRAINT ck_dispatch_outbox_attempts CHECK (attempts >= 0)
);

CREATE INDEX ix_dispatch_outbox_unpublished ON dispatch.outbox_event (occurred_at)
    WHERE published_at IS NULL;
