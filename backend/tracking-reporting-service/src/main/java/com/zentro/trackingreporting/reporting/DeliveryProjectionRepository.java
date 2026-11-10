package com.zentro.trackingreporting.reporting;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Updates only this parcel and the old/new contribution days, under database locks. */
@Repository
public class DeliveryProjectionRepository {
  private final JdbcTemplate jdbc;

  public DeliveryProjectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /** Must be called inside the consumer transaction, before inserting history. */
  public void lock(UUID parcelId) {
    jdbc.update("INSERT INTO tracking.delivery_projection(parcel_id) VALUES (?) ON CONFLICT DO NOTHING", parcelId);
    jdbc.queryForObject("SELECT parcel_id FROM tracking.delivery_projection WHERE parcel_id = ? FOR UPDATE",
        UUID.class, parcelId);
  }

  public void refresh(UUID parcelId) {
    Map<String, Object> before = jdbc.queryForMap("SELECT * FROM tracking.delivery_projection WHERE parcel_id = ?", parcelId);
    // Terminal statuses are final in S2. Earliest business-time terminal fact wins, with UUID as a stable tie.
    jdbc.update("""
        UPDATE tracking.delivery_projection p SET
          created_at = (SELECT min(occurred_at) FROM tracking.tracking_event
                         WHERE parcel_id = p.parcel_id AND event_id IS NOT NULL AND status = 'CREATED'),
          terminal_at = (SELECT occurred_at FROM tracking.tracking_event WHERE parcel_id = p.parcel_id
                         AND event_id IS NOT NULL AND status IN ('DELIVERED','FAILED') ORDER BY occurred_at, event_id LIMIT 1),
          terminal_status = (SELECT status FROM tracking.tracking_event WHERE parcel_id = p.parcel_id
                         AND event_id IS NOT NULL AND status IN ('DELIVERED','FAILED') ORDER BY occurred_at, event_id LIMIT 1),
          current_status = (SELECT status FROM tracking.tracking_event WHERE parcel_id = p.parcel_id AND event_id IS NOT NULL
                         ORDER BY CASE WHEN status IN ('DELIVERED','FAILED') THEN 1 ELSE 0 END DESC,
                         occurred_at DESC, event_id DESC LIMIT 1),
          current_at = (SELECT max(occurred_at) FROM tracking.tracking_event WHERE parcel_id = p.parcel_id AND event_id IS NOT NULL)
        WHERE p.parcel_id = ?
        """, parcelId);
    // For terminal parcels current state agrees with the chosen terminal contribution.
    jdbc.update("UPDATE tracking.delivery_projection SET current_status = terminal_status WHERE parcel_id = ? AND terminal_at IS NOT NULL", parcelId);
    Map<String, Object> after = jdbc.queryForMap("SELECT * FROM tracking.delivery_projection WHERE parcel_id = ?", parcelId);
    var deltas = new TreeMap<java.time.LocalDate, long[]>();
    contributions(before, -1, deltas);
    contributions(after, 1, deltas);
    jdbc.queryForList("SELECT DISTINCT tracking.reporting_day(occurred_at) FROM tracking.tracking_event WHERE parcel_id = ? AND event_id IS NOT NULL",
        java.time.LocalDate.class, parcelId).forEach(day -> deltas.computeIfAbsent(day, ignored -> new long[3]));
    // All consumers take day locks in chronological order, avoiding cross-parcel day-lock inversion.
    deltas.forEach((day, delta) -> {
      jdbc.update("INSERT INTO tracking.delivery_daily(day) VALUES (?) ON CONFLICT DO NOTHING", day);
      if (delta[0] == 0 && delta[1] == 0 && delta[2] == 0) return;
      jdbc.update("""
          UPDATE tracking.delivery_daily SET created = created + ?, delivered = delivered + ?, failed = failed + ?
          WHERE day = ?
          """, delta[0], delta[1], delta[2], day);
    });
    // Identity-bearing tracking parcels may have been provisioned separately. Never invent their fields.
    jdbc.update("""
        UPDATE tracking.tracking_parcel t SET status = p.current_status,
          delivered_at = CASE WHEN p.terminal_status = 'DELIVERED' THEN p.terminal_at ELSE NULL END,
          failure_reason = CASE WHEN p.terminal_status = 'FAILED' THEN
            (SELECT failure_reason FROM tracking.tracking_event WHERE parcel_id = p.parcel_id
             AND event_id IS NOT NULL AND status IN ('DELIVERED','FAILED') ORDER BY occurred_at, event_id LIMIT 1) ELSE NULL END
        FROM tracking.delivery_projection p WHERE p.parcel_id = ? AND t.parcel_id = p.parcel_id
        """, parcelId);
  }

  private void contributions(Map<String, Object> row, int sign, Map<java.time.LocalDate, long[]> deltas) {
    add(row.get("created_at"), 0, sign, deltas);
    if (row.get("terminal_at") != null) {
      add(row.get("terminal_at"), "DELIVERED".equals(row.get("terminal_status")) ? 1 : 2, sign, deltas);
    }
  }

  private void add(Object timestamp, int column, int sign, Map<java.time.LocalDate, long[]> deltas) {
    if (timestamp == null) return;
    // Resolve day through the same database function used by the view and recount.
    var day = jdbc.queryForObject("SELECT tracking.reporting_day(?)", java.time.LocalDate.class,
        timestamp instanceof java.sql.Timestamp t ? t.toInstant().atOffset(java.time.ZoneOffset.UTC) : timestamp);
    deltas.computeIfAbsent(day, ignored -> new long[3])[column] += sign;
  }
}
