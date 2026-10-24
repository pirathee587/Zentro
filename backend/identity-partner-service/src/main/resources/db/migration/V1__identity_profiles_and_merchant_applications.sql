CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    keycloak_subject VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(320),
    display_name VARCHAR(200),
    phone VARCHAR(32),
    account_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT app_user_account_status_check
        CHECK (account_status IN ('ACTIVE', 'DISABLED', 'SUSPENDED'))
);

CREATE TABLE identity.merchant_application (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id UUID NOT NULL UNIQUE REFERENCES identity.app_user(id) ON DELETE CASCADE,
    business_name VARCHAR(140) NOT NULL,
    business_reg_no VARCHAR(80),
    contact_phone VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    rejection_reason VARCHAR(1000),
    reviewed_by_subject VARCHAR(255),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT merchant_application_status_check
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT merchant_application_review_consistency_check
        CHECK ((status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by_subject IS NULL)
            OR (status <> 'PENDING' AND reviewed_at IS NOT NULL AND reviewed_by_subject IS NOT NULL)),
    CONSTRAINT merchant_application_rejection_reason_check
        CHECK ((status <> 'REJECTED') OR rejection_reason IS NOT NULL)
);

CREATE INDEX merchant_application_status_created_idx
    ON identity.merchant_application (status, created_at DESC);
