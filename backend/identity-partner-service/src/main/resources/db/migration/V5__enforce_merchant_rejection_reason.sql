ALTER TABLE identity.merchant_application
  ADD COLUMN IF NOT EXISTS rejection_reason TEXT;

ALTER TABLE identity.merchant_application
  ADD CONSTRAINT merchant_rejection_reason_required
  CHECK (status <> 'REJECTED' OR NULLIF(BTRIM(rejection_reason), '') IS NOT NULL);
