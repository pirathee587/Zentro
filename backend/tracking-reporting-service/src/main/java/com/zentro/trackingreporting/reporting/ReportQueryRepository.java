package com.zentro.trackingreporting.reporting;

import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(readOnly = true)
public class ReportQueryRepository {
  private final JdbcTemplate jdbc;

  public ReportQueryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<DailyDeliveryRow> daily(LocalDate from, LocalDate to) {
    return jdbc.query("""
        SELECT day, created, delivered, failed, active, success_rate
        FROM tracking.delivery_daily_metrics WHERE day BETWEEN ? AND ? ORDER BY day
        """, (row, index) -> new DailyDeliveryRow(row.getObject("day", LocalDate.class),
            row.getLong("created"), row.getLong("delivered"), row.getLong("failed"),
            row.getLong("active"), row.getBigDecimal("success_rate")), from, to);
  }
}
