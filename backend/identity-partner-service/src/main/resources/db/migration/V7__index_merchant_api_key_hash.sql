-- Index on key_hash to accelerate SHA-256 lookup during merchant API key validation
CREATE INDEX IF NOT EXISTS idx_merchant_api_key_hash ON identity.merchant_api_key(key_hash);
