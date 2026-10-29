CREATE UNIQUE INDEX merchant_application_business_reg_no_uq
    ON identity.merchant_application (LOWER(business_reg_no))
    WHERE business_reg_no IS NOT NULL;
