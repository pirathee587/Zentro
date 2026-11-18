ALTER TABLE dispatch.delivery_assignment
  ADD COLUMN IF NOT EXISTS assignment_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_ACCEPTANCE',
  ADD COLUMN IF NOT EXISTS responded_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(500),
  ADD COLUMN IF NOT EXISTS courier_name VARCHAR(200),
  ADD COLUMN IF NOT EXISTS courier_phone VARCHAR(40),
  ADD COLUMN IF NOT EXISTS courier_vehicle VARCHAR(100),
  ADD COLUMN IF NOT EXISTS courier_zone VARCHAR(100);

ALTER TABLE dispatch.delivery_assignment
  DROP CONSTRAINT IF EXISTS chk_delivery_assignment_status;

ALTER TABLE dispatch.delivery_assignment
  ADD CONSTRAINT chk_delivery_assignment_status
    CHECK (assignment_status IN ('PENDING_ACCEPTANCE', 'ACCEPTED', 'REJECTED'));

ALTER TABLE dispatch.assignment_audit
  DROP CONSTRAINT IF EXISTS ck_assignment_audit_action;

ALTER TABLE dispatch.assignment_audit
  ADD CONSTRAINT ck_assignment_audit_action
    CHECK (action IN ('ASSIGNED', 'REASSIGNED', 'CANCELLED', 'ACCEPTED', 'REJECTED'));
