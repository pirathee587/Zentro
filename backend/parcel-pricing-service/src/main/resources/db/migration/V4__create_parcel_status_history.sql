-- One row per accepted parcel status change: who changed it, from what, to what and when.
-- Rows are append-only; a trigger rejects any UPDATE or DELETE.
CREATE TABLE parcel.parcel_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    parcel_id UUID NOT NULL REFERENCES parcel.parcel (id),
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(200),
    note VARCHAR(500),
    actor_subject VARCHAR(255) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_status_history_from CHECK (from_status IN (
        'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_status_history_to CHECK (to_status IN (
        'CREATED', 'PICKED_UP', 'IN_TRANSIT', 'OUT_FOR_DELIVERY', 'DELIVERED', 'FAILED'))
);

CREATE INDEX idx_status_history_parcel_time ON parcel.parcel_status_history (parcel_id, occurred_at);

CREATE FUNCTION parcel.reject_status_history_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'parcel_status_history is append-only; % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER trg_status_history_immutable
    BEFORE UPDATE OR DELETE ON parcel.parcel_status_history
    FOR EACH ROW EXECUTE FUNCTION parcel.reject_status_history_change();
