package com.zentro.dispatchcourier.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OutboxRepository {
  public record PendingEvent(UUID eventId, UUID aggregateId, String eventType, String payload, int attempts) {}
  private final JdbcTemplate jdbc;

  public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Transactional(propagation = Propagation.MANDATORY)
  public List<PendingEvent> fetchBatch(int maxAttempts, int batchSize) {
    return jdbc.query("""
        SELECT event_id, aggregate_id, event_type, payload::text AS payload, attempts
          FROM dispatch.outbox_event
         WHERE published_at IS NULL AND attempts < ?
           AND (next_attempt_at IS NULL OR next_attempt_at <= now())
         ORDER BY occurred_at
         LIMIT ? FOR UPDATE SKIP LOCKED
        """, (rs, row) -> new PendingEvent(rs.getObject("event_id", UUID.class),
            rs.getObject("aggregate_id", UUID.class), rs.getString("event_type"),
            rs.getString("payload"), rs.getInt("attempts")), maxAttempts, batchSize);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markPublished(UUID eventId) {
    jdbc.update("UPDATE dispatch.outbox_event SET published_at = now(), next_attempt_at = NULL, last_error = NULL WHERE event_id = ?",
        eventId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markFailed(UUID eventId, String error, OffsetDateTime nextAttemptAt) {
    String safeError = error == null ? null : error.substring(0, Math.min(500, error.length()));
    jdbc.update("""
        UPDATE dispatch.outbox_event
           SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?
         WHERE event_id = ?
        """, safeError, nextAttemptAt, eventId);
  }
}
