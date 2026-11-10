package com.zentro.trackingreporting.integration;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class ProjectionMigrationIntegrationTest {
  @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
  @Test void priorVersionHistoryBackfillsWithoutForeignDatabaseAccess() {
    Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("2").load().migrate();
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    UUID parcel = UUID.randomUUID();
    for (String status : new String[]{"CREATED", "DELIVERED"}) jdbc.update("""
        INSERT INTO tracking.tracking_event(event_id, parcel_id, tracking_ref, status, title, occurred_at)
        VALUES (?, ?, 'ZT-7K3QX9M2PA', ?, 'Synthetic migration fact', '2026-11-01T18:30:00Z')
        """, UUID.randomUUID(), parcel, status);
    var migration = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load();
    assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
    migration.validate();
    assertThat(jdbc.queryForMap("SELECT * FROM tracking.delivery_daily_metrics"))
        .containsEntry("created", 1L).containsEntry("delivered", 1L).containsEntry("active", 0L);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking.tracking_event", Long.class)).isEqualTo(2);
    jdbc.execute("""
        INSERT INTO tracking.tracking_event(event_id, parcel_id, tracking_ref, status, title)
        SELECT gen_random_uuid(), gen_random_uuid(), 'ZT-7K3QX9M2PA', 'CREATED', 'Synthetic plan fixture'
        FROM generate_series(1, 10000)
        """);
    jdbc.execute("ANALYZE tracking.tracking_event");
    var plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM tracking.tracking_event WHERE parcel_id = ? AND event_id IS NOT NULL ORDER BY occurred_at, event_id", String.class, parcel);
    System.out.println("S4_HISTORY_EXPLAIN " + String.join(" | ", plan));
    assertThat(String.join(" ", plan)).contains("idx_tracking_event_parcel_time");
  }
}
