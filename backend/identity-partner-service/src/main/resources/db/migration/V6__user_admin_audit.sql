-- ZD-042 (#117): append-only record of user changes made by dispatchers.
-- One row per real change (a request that changes nothing writes no row). changed_fields holds field names only,
-- never the old or new names, phone numbers or emails. Status values are stored because they are not personal data.
-- No foreign keys on purpose: audit rows must outlive the users they describe.
CREATE TABLE identity.user_admin_audit (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID NOT NULL,
    actor_subject VARCHAR(255) NOT NULL,
    target_user_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    changed_fields TEXT[] NOT NULL,
    old_status VARCHAR(16),
    new_status VARCHAR(16),
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_admin_audit_action_check CHECK (action IN ('PROFILE_UPDATED', 'STATUS_CHANGED')),
    CONSTRAINT user_admin_audit_fields_check CHECK (cardinality(changed_fields) > 0),
    CONSTRAINT user_admin_audit_status_check CHECK (
        (old_status IS NULL OR old_status IN ('ACTIVE', 'DISABLED', 'SUSPENDED'))
        AND (new_status IS NULL OR new_status IN ('ACTIVE', 'DISABLED', 'SUSPENDED'))),
    CONSTRAINT user_admin_audit_status_change_check CHECK (
        action <> 'STATUS_CHANGED' OR (old_status IS NOT NULL AND new_status IS NOT NULL AND old_status <> new_status))
);

CREATE INDEX idx_user_admin_audit_target ON identity.user_admin_audit (target_user_id, occurred_at DESC);
CREATE INDEX idx_user_admin_audit_actor ON identity.user_admin_audit (actor_user_id, occurred_at DESC);

-- Audit rows are immutable: every UPDATE, DELETE and TRUNCATE is rejected.
CREATE FUNCTION identity.reject_user_admin_audit_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'identity.user_admin_audit is append-only';
END;
$$;

CREATE TRIGGER user_admin_audit_no_update_delete
    BEFORE UPDATE OR DELETE ON identity.user_admin_audit
    FOR EACH ROW EXECUTE FUNCTION identity.reject_user_admin_audit_change();

CREATE TRIGGER user_admin_audit_no_truncate
    BEFORE TRUNCATE ON identity.user_admin_audit
    FOR EACH STATEMENT EXECUTE FUNCTION identity.reject_user_admin_audit_change();
