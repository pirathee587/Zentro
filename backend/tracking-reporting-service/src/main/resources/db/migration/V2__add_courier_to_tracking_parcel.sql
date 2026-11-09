ALTER TABLE tracking.tracking_parcel ADD COLUMN IF NOT EXISTS courier_subject VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_tracking_parcel_courier ON tracking.tracking_parcel (courier_subject);
CREATE INDEX IF NOT EXISTS idx_tracking_parcel_merchant ON tracking.tracking_parcel (merchant_subject);

