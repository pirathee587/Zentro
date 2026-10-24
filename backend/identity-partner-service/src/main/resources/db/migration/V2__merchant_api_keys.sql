CREATE TABLE identity.merchant_api_key (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id UUID NOT NULL UNIQUE REFERENCES identity.app_user(id) ON DELETE CASCADE,
    key_prefix VARCHAR(32) NOT NULL UNIQUE,
    key_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    rotated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    CONSTRAINT merchant_api_key_prefix_check CHECK (key_prefix LIKE 'zn_live_%')
);
