-- ZD-050 (#125): add SUSPENDED to the merchant application approval lifecycle.
-- A suspended merchant cannot perform approved-merchant operations (API keys, orders, webhooks).
-- Only a DISPATCHER can suspend an approved merchant or reinstate a suspended one.

-- 1. Extend the status check to include SUSPENDED.
ALTER TABLE identity.merchant_application
  DROP CONSTRAINT merchant_application_status_check;

ALTER TABLE identity.merchant_application
  ADD CONSTRAINT merchant_application_status_check
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'));

-- 2. The review-consistency check already requires reviewed_at / reviewed_by_subject to be
--    set for every non-PENDING status, which is correct for SUSPENDED too — no change needed.
--    (Constraint merchant_application_review_consistency_check is left in place.)

-- 3. Add a reason column for suspension notes (reuses the existing rejection_reason column
--    semantics: populated for REJECTED and SUSPENDED, NULL for APPROVED/PENDING).
--    The column may already exist from V5; use ADD COLUMN IF NOT EXISTS to be safe.
ALTER TABLE identity.merchant_application
  ADD COLUMN IF NOT EXISTS suspension_reason VARCHAR(1000);

-- 4. Update the status index to cover SUSPENDED queries efficiently.
DROP INDEX IF EXISTS merchant_application_status_created_idx;
CREATE INDEX merchant_application_status_created_idx
    ON identity.merchant_application (status, created_at DESC);
