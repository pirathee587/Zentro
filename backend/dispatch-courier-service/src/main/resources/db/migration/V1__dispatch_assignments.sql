CREATE SCHEMA IF NOT EXISTS dispatch;

-- Initial assignment only. No cross-service foreign keys or inferred lifecycle rules.
-- Reassignment/release needs a separately agreed lifecycle and a future migration.
CREATE TABLE dispatch.delivery_assignment (
    parcel_id UUID PRIMARY KEY,
    courier_profile_id UUID NOT NULL,
    courier_user_id UUID NOT NULL,
    assigned_by_subject VARCHAR(255) NOT NULL CHECK (length(trim(assigned_by_subject)) > 0),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_delivery_assignment_courier ON dispatch.delivery_assignment (courier_profile_id);
