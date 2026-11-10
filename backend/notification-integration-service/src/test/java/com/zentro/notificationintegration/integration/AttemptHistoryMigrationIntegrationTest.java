package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class AttemptHistoryMigrationIntegrationTest {
  @Container static final PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");
  @Test void existingSummaryIsPreservedAndOnlyNewAttemptsAreCaptured() {
    Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("3").load().migrate();
    var jdbc = new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
    UUID id = UUID.randomUUID(), event = UUID.randomUUID();
    jdbc.update("""
        INSERT INTO notification.notification_attempt(id, event_id, event_type, parcel_id, tracking_ref, parcel_status, message, status, attempts)
        VALUES (?, ?, 'parcel.created', ?, 'ZT-7K3QX9M2PA', 'CREATED', 'Synthetic legacy summary', 'RETRY', 5)
        """, id, event, UUID.randomUUID());
    var migration = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load();
    assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
    migration.validate();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM notification.delivery_attempt_history", Long.class)).isZero();
    jdbc.update("UPDATE notification.notification_attempt SET attempts = 6, status = 'SENT' WHERE id = ?", id);
    assertThat(jdbc.queryForMap("SELECT * FROM notification.delivery_attempt_history"))
        .containsEntry("attempt_number", 1).containsEntry("policy_attempt_number", 6).containsEntry("outcome", "SENT");
    assertThat(jdbc.queryForObject("SELECT message FROM notification.notification_attempt WHERE id = ?", String.class, id))
        .isEqualTo("Synthetic legacy summary");
    jdbc.execute("""
        INSERT INTO notification.delivery_attempt_history
          (delivery_kind, delivery_id, event_id, attempt_number, policy_attempt_number, outcome, response_class, started_at, finished_at, terminal)
        SELECT 'NOTIFICATION', gen_random_uuid(), gen_random_uuid(), 1, 1, 'SENT', 'SUCCESS', now(), now(), true
        FROM generate_series(1, 10000)
        """);
    jdbc.execute("ANALYZE notification.delivery_attempt_history");
    var plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM notification.delivery_attempt_history WHERE delivery_kind = 'NOTIFICATION' AND delivery_id = ? ORDER BY attempt_number", String.class, id);
    System.out.println("S5_ATTEMPT_EXPLAIN " + String.join(" | ", plan));
    assertThat(String.join(" ", plan)).contains("delivery_attempt_history_pkey");
  }
}
