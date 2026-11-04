CREATE TABLE IF NOT EXISTS identity.courier_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    app_user_id UUID NOT NULL UNIQUE REFERENCES identity.app_user(id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT true,
    assigned_zone VARCHAR(64),
    vehicle_type VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT courier_profile_vehicle_type_check
        CHECK (vehicle_type IS NULL OR vehicle_type IN ('BICYCLE', 'MOTORCYCLE', 'CAR', 'VAN', 'TRUCK'))
);

CREATE INDEX IF NOT EXISTS courier_profile_app_user_id_idx
    ON identity.courier_profile (app_user_id);

CREATE INDEX IF NOT EXISTS courier_profile_is_active_idx
    ON identity.courier_profile (is_active);
