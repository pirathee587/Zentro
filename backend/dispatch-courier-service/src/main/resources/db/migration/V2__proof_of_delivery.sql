CREATE TABLE dispatch.proof_of_delivery (
    id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL UNIQUE REFERENCES dispatch.delivery_assignment(parcel_id),
    recipient_name VARCHAR(100) NOT NULL CHECK (recipient_name ~ '[^[:space:]]'),
    notes VARCHAR(500),
    proof_reference VARCHAR(200) NOT NULL CHECK (proof_reference ~ '[^[:space:]]'),
    submitted_by_subject VARCHAR(255) NOT NULL CHECK (length(btrim(submitted_by_subject)) > 0),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completion_state VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (completion_state IN ('PENDING', 'COMPLETED', 'REJECTED', 'BLOCKED')),
    completed_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token UUID,
    last_error_code VARCHAR(64),
    CHECK ((completion_state = 'COMPLETED') = (completed_at IS NOT NULL))
);
CREATE INDEX ix_proof_due ON dispatch.proof_of_delivery(next_attempt_at)
    WHERE completion_state = 'PENDING';
CREATE INDEX ix_assignment_courier_user ON dispatch.delivery_assignment(courier_user_id);

-- Recovery may change completion bookkeeping, never the accepted evidence or association.
CREATE FUNCTION dispatch.protect_proof_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Proof evidence is immutable';
    END IF;
    IF ROW(NEW.id, NEW.parcel_id, NEW.recipient_name, NEW.notes, NEW.proof_reference,
           NEW.submitted_by_subject, NEW.submitted_at)
       IS DISTINCT FROM
       ROW(OLD.id, OLD.parcel_id, OLD.recipient_name, OLD.notes, OLD.proof_reference,
           OLD.submitted_by_subject, OLD.submitted_at) THEN
        RAISE EXCEPTION 'Proof evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_proof_immutable BEFORE UPDATE OR DELETE ON dispatch.proof_of_delivery
    FOR EACH ROW EXECUTE FUNCTION dispatch.protect_proof_evidence();
