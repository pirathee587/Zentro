WITH history AS (
    SELECT * FROM tracking.tracking_event WHERE event_id IS NOT NULL AND parcel_id IS NOT NULL
), creations AS (
    SELECT parcel_id, min(occurred_at) AS created_at FROM history WHERE status = 'CREATED' GROUP BY parcel_id
), terminals AS (
    SELECT DISTINCT ON (parcel_id) parcel_id, occurred_at AS terminal_at, status
    FROM history WHERE status IN ('DELIVERED','FAILED') ORDER BY parcel_id, occurred_at, event_id
), dates AS (
    SELECT generate_series(min(tracking.reporting_day(occurred_at))::timestamp,
                           max(tracking.reporting_day(occurred_at))::timestamp, interval '1 day')::date AS day FROM history
), counts AS (
    SELECT day,
      (SELECT count(*) FROM creations c WHERE tracking.reporting_day(c.created_at) = day) AS created,
      (SELECT count(*) FROM terminals t WHERE tracking.reporting_day(t.terminal_at) = day AND status = 'DELIVERED') AS delivered,
      (SELECT count(*) FROM terminals t WHERE tracking.reporting_day(t.terminal_at) = day AND status = 'FAILED') AS failed,
      (SELECT count(*) FROM creations c LEFT JOIN terminals t USING (parcel_id)
       WHERE tracking.reporting_day(c.created_at) <= day AND (t.terminal_at IS NULL OR tracking.reporting_day(t.terminal_at) > day)) AS active
    FROM dates
)
SELECT *, delivered::numeric / NULLIF(delivered + failed, 0) AS success_rate FROM counts ORDER BY day
