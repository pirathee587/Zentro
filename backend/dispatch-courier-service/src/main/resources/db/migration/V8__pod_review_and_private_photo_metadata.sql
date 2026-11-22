-- PREPARED ONLY: do not apply this migration to the shared S3 database without
-- the explicit approval procedure in docs/operations/pod-review-supabase-approval.md.
--
-- Existing proof_of_delivery rows remain immutable legacy evidence. New evidence
-- is versioned, so a rejected photo never overwrites a prior submission.

CREATE TABLE dispatch.proof_revision (
    id UUID PRIMARY KEY,
    proof_id UUID NOT NULL REFERENCES dispatch.proof_of_delivery(id),
    revision_no INTEGER NOT NULL CHECK (revision_no > 0),
    object_path VARCHAR(512),
    original_filename VARCHAR(255),
    content_type VARCHAR(32),
    byte_size BIGINT CHECK (byte_size IS NULL OR byte_size > 0 AND byte_size <= 5242880),
    content_sha256 CHAR(64),
    submitted_by_subject VARCHAR(255) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (proof_id, revision_no),
    UNIQUE (object_path)
);

-- A storage upload is deliberately staged before it becomes evidence.  A stage
-- is invisible to dispatcher/courier reads until finalization creates the
-- immutable revision and review in the same database transaction.
CREATE TABLE dispatch.proof_upload_stage (
    id UUID PRIMARY KEY,
    parcel_id UUID NOT NULL REFERENCES dispatch.delivery_assignment(parcel_id),
    courier_subject VARCHAR(255) NOT NULL,
    idempotency_key UUID NOT NULL,
    object_path VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(32) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    byte_size BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 5242880),
    content_sha256 CHAR(64) NOT NULL,
    proof_id UUID REFERENCES dispatch.proof_of_delivery(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('UPLOADING', 'UPLOADED', 'FINALIZED', 'ABANDONED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    UNIQUE (parcel_id, courier_subject, idempotency_key)
);
CREATE INDEX ix_proof_upload_stage_cleanup ON dispatch.proof_upload_stage(state, expires_at)
    WHERE state = 'UPLOADED';

ALTER TABLE dispatch.proof_revision
    ADD CONSTRAINT ck_proof_revision_photo_metadata
    CHECK ((object_path IS NULL AND original_filename IS NULL AND content_type IS NULL AND byte_size IS NULL AND content_sha256 IS NULL)
       OR (object_path IS NOT NULL AND original_filename IS NOT NULL
           AND content_type IN ('image/jpeg', 'image/png', 'image/webp')
           AND byte_size IS NOT NULL AND content_sha256 IS NOT NULL));

CREATE TABLE dispatch.proof_review (
    id UUID PRIMARY KEY,
    revision_id UUID NOT NULL UNIQUE REFERENCES dispatch.proof_revision(id),
    decision VARCHAR(24) NOT NULL CHECK (decision IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'COMPLETION_PENDING', 'COMPLETION_PROCESSING', 'COMPLETED', 'COMPLETION_FAILED', 'BLOCKED')),
    reviewed_by_subject VARCHAR(255),
    reviewed_at TIMESTAMPTZ,
    rejection_reason VARCHAR(500),
    completion_error_code VARCHAR(64),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((decision = 'REJECTED') = (rejection_reason IS NOT NULL)),
    CHECK ((decision IN ('APPROVED', 'COMPLETION_PENDING', 'COMPLETED', 'COMPLETION_FAILED', 'BLOCKED'))
           = (reviewed_by_subject IS NOT NULL AND reviewed_at IS NOT NULL))
);

CREATE TABLE dispatch.proof_review_audit (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES dispatch.proof_review(id),
    from_decision VARCHAR(24),
    to_decision VARCHAR(24) NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE dispatch.proof_completion_attempt (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL REFERENCES dispatch.proof_review(id),
    requested_by_subject VARCHAR(255) NOT NULL,
    outcome VARCHAR(24) NOT NULL CHECK (outcome IN ('CONFIRMED', 'RETRYABLE_FAILURE', 'BLOCKED_FAILURE')),
    error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_proof_completion_attempt_review ON dispatch.proof_completion_attempt(review_id, created_at DESC);

CREATE INDEX ix_proof_review_pending ON dispatch.proof_review(decision, created_at)
    WHERE decision IN ('PENDING_REVIEW', 'COMPLETION_PENDING', 'COMPLETION_FAILED', 'BLOCKED');

CREATE FUNCTION dispatch.protect_proof_revision_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Proof revision evidence is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_proof_revision_immutable BEFORE UPDATE OR DELETE ON dispatch.proof_revision
    FOR EACH ROW EXECUTE FUNCTION dispatch.protect_proof_revision_evidence();

CREATE FUNCTION dispatch.protect_proof_review_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Proof review audit is append-only';
END;
$$;
CREATE TRIGGER trg_proof_review_audit_immutable BEFORE UPDATE OR DELETE ON dispatch.proof_review_audit
    FOR EACH ROW EXECUTE FUNCTION dispatch.protect_proof_review_audit();
