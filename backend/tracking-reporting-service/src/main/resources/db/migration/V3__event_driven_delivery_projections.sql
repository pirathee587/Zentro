-- No identity fields are available in parcel events; do not fabricate tracking_parcel records.
CREATE TABLE tracking.delivery_projection (
    parcel_id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ,
    terminal_at TIMESTAMPTZ,
    terminal_status VARCHAR(16) CHECK (terminal_status IN ('DELIVERED', 'FAILED')),
    current_status VARCHAR(32),
    current_at TIMESTAMPTZ
);
CREATE TABLE tracking.delivery_daily (
    day DATE PRIMARY KEY,
    created BIGINT NOT NULL DEFAULT 0 CHECK (created >= 0),
    delivered BIGINT NOT NULL DEFAULT 0 CHECK (delivered >= 0),
    failed BIGINT NOT NULL DEFAULT 0 CHECK (failed >= 0)
);

-- One definition of the reporting timezone, independent of the database/JVM session timezone.
CREATE FUNCTION tracking.reporting_day(value TIMESTAMPTZ) RETURNS DATE
    LANGUAGE SQL IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT (value AT TIME ZONE 'Asia/Colombo')::DATE
$$;

-- Empty intervening days are included. Active means known-created, not terminal by that day's end.
CREATE VIEW tracking.delivery_daily_metrics AS
SELECT dates.day::DATE AS day, COALESCE(d.created, 0) AS created,
       COALESCE(d.delivered, 0) AS delivered, COALESCE(d.failed, 0) AS failed,
       (SELECT count(*) FROM tracking.delivery_projection p
         WHERE tracking.reporting_day(p.created_at) <= dates.day::DATE
           AND (p.terminal_at IS NULL OR tracking.reporting_day(p.terminal_at) > dates.day::DATE)) AS active,
       d.delivered::NUMERIC / NULLIF(d.delivered + d.failed, 0) AS success_rate
FROM (SELECT generate_series(min(day)::TIMESTAMP, max(day)::TIMESTAMP, interval '1 day') AS day
      FROM tracking.delivery_daily) dates
LEFT JOIN tracking.delivery_daily d ON d.day = dates.day::DATE;

-- Backfill from S4's own durable event history only. Legacy rows without stable event IDs are excluded.
INSERT INTO tracking.delivery_projection(parcel_id, created_at, terminal_at, terminal_status, current_status, current_at)
SELECT ids.parcel_id, c.created_at, t.occurred_at, t.status,
       COALESCE(t.status, latest.status), latest.occurred_at
FROM (SELECT DISTINCT parcel_id FROM tracking.tracking_event WHERE event_id IS NOT NULL AND parcel_id IS NOT NULL) ids
LEFT JOIN LATERAL (SELECT min(occurred_at) AS created_at FROM tracking.tracking_event
    WHERE parcel_id = ids.parcel_id AND event_id IS NOT NULL AND status = 'CREATED') c ON true
LEFT JOIN LATERAL (SELECT occurred_at, status FROM tracking.tracking_event
    WHERE parcel_id = ids.parcel_id AND event_id IS NOT NULL AND status IN ('DELIVERED','FAILED')
    ORDER BY occurred_at, event_id LIMIT 1) t ON true
LEFT JOIN LATERAL (SELECT occurred_at, status FROM tracking.tracking_event
    WHERE parcel_id = ids.parcel_id AND event_id IS NOT NULL ORDER BY occurred_at DESC, event_id DESC LIMIT 1) latest ON true;

INSERT INTO tracking.delivery_daily(day, created, delivered, failed)
SELECT days.day,
       (SELECT count(*) FROM tracking.delivery_projection p WHERE tracking.reporting_day(p.created_at) = days.day),
       (SELECT count(*) FROM tracking.delivery_projection p WHERE tracking.reporting_day(p.terminal_at) = days.day AND p.terminal_status = 'DELIVERED'),
       (SELECT count(*) FROM tracking.delivery_projection p WHERE tracking.reporting_day(p.terminal_at) = days.day AND p.terminal_status = 'FAILED')
FROM (SELECT DISTINCT tracking.reporting_day(occurred_at) AS day FROM tracking.tracking_event
      WHERE event_id IS NOT NULL AND parcel_id IS NOT NULL) days;
