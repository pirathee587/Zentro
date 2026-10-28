CREATE SCHEMA IF NOT EXISTS pricing;

CREATE TABLE pricing.delivery_zone (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(256),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pricing.pricing_rule (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_name VARCHAR(128) NOT NULL UNIQUE,
    base_fee NUMERIC(10, 2) NOT NULL DEFAULT 550.00,
    included_weight_kg NUMERIC(6, 2) NOT NULL DEFAULT 1.00,
    additional_weight_rate_per_kg NUMERIC(10, 2) NOT NULL DEFAULT 120.00,
    distance_surcharge_other_district NUMERIC(10, 2) NOT NULL DEFAULT 402.00,
    express_surcharge NUMERIC(10, 2) NOT NULL DEFAULT 300.00,
    per_km_rate NUMERIC(10, 2) NOT NULL DEFAULT 0.00,
    currency VARCHAR(3) NOT NULL DEFAULT 'LKR',
    active BOOLEAN NOT NULL DEFAULT true,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed approved delivery zones
INSERT INTO pricing.delivery_zone (code, name, description) VALUES
    ('ZONE_COLOMBO_METRO', 'Colombo Metro', 'Western Province - Colombo District primary coverage area'),
    ('ZONE_WESTERN', 'Western Province (Outer)', 'Gampaha and Kalutara districts'),
    ('ZONE_CENTRAL', 'Central Province', 'Kandy, Matale, Nuwara Eliya districts'),
    ('ZONE_SOUTHERN', 'Southern Province', 'Galle, Matara, Hambantota districts'),
    ('ZONE_NORTHERN', 'Northern Province', 'Jaffna, Kilinochchi, Mannar, Vavuniya, Mullaitivu districts')
ON CONFLICT (code) DO NOTHING;

-- Seed default pricing rule with approved rates
INSERT INTO pricing.pricing_rule (
    rule_name,
    base_fee,
    included_weight_kg,
    additional_weight_rate_per_kg,
    distance_surcharge_other_district,
    express_surcharge,
    per_km_rate,
    currency,
    active
) VALUES (
    'STANDARD_DEFAULT',
    550.00,
    1.00,
    120.00,
    402.00,
    300.00,
    0.00,
    'LKR',
    true
)
ON CONFLICT (rule_name) DO NOTHING;
