CREATE TABLE identity.registration_otp (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    code_hash VARCHAR(128) NOT NULL,
    entry_point VARCHAR(32) NOT NULL DEFAULT 'customer',
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ
);

CREATE INDEX registration_otp_email_idx ON identity.registration_otp(email, created_at DESC);
