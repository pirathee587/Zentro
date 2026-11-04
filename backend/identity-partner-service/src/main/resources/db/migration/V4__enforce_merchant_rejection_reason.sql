ALTER TABLE identity.merchant_application
    DROP CONSTRAINT merchant_application_rejection_reason_check;

ALTER TABLE identity.merchant_application
    ADD CONSTRAINT merchant_application_rejection_reason_check
    CHECK ((status <> 'REJECTED')
        OR (rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0));
