package com.zentro.parcelpricing.outbox.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JdbcTemplate access to {@code parcel.outbox_event} for the publisher. */
@Repository
public class OutboxRepository {
  static final int MAX_ERROR_LENGTH = 500;

  /** One unpublished event. {@code payload} is the full event envelope JSON as stored. */
  public record PendingEvent(UUID eventId, UUID aggregateId, String eventType, String payload, int attempts) {}

  private final JdbcTemplate jdbc;

  public OutboxRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Locks and returns up to {@code batchSize} events that are due to be sent, oldest first.
   * An event is skipped while an earlier unpublished event of the same parcel exists, so each parcel's events
   * are sent in order. {@code SKIP LOCKED} lets several service instances publish without sending the same row.
   * Must run inside the caller's transaction; the locks are held until it ends.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<PendingEvent> fetchBatch(int maxAttempts, int batchSize) {
    return jdbc.query("""
        SELECT o.event_id, o.aggregate_id, o.event_type, o.payload::text AS payload, o.attempts
          FROM parcel.outbox_event o
         WHERE o.published_at IS NULL
           AND o.attempts < ?
           AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
           AND NOT EXISTS (SELECT 1 FROM parcel.outbox_event p
                            WHERE p.aggregate_id = o.aggregate_id
                              AND p.published_at IS NULL
                              AND p.occurred_at < o.occurred_at)
         ORDER BY o.occurred_at
         LIMIT ?
         FOR UPDATE OF o SKIP LOCKED
        """,
        (rs, rowNum) -> new PendingEvent(
            rs.getObject("event_id", UUID.class),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getInt("attempts")),
        maxAttempts, batchSize);
  }

  /** Marks an event as sent and clears its retry information. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void markPublished(UUID eventId) {
    jdbc.update("""
        UPDATE parcel.outbox_event
           SET published_at = now(), next_attempt_at = NULL, last_error = NULL
         WHERE event_id = ?
        """, eventId);
  }

  /**
   * Records one failed attempt.
   *
   * @param error short description of the failure; must not contain the payload. Cut to 500 characters.
   * @return the attempt count after this failure
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public int markFailed(UUID eventId, String error, OffsetDateTime nextAttemptAt) {
    String trimmed = error == null ? null
        : error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    Integer attempts = jdbc.queryForObject("""
        UPDATE parcel.outbox_event
           SET attempts = attempts + 1, last_error = ?, next_attempt_at = ?
         WHERE event_id = ?
        RETURNING attempts
        """, Integer.class, trimmed, nextAttemptAt, eventId);
    return attempts == null ? 0 : attempts;
  }

  /** Unpublished events that will still be retried. */
  public long countPending(int maxAttempts) {
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM parcel.outbox_event WHERE published_at IS NULL AND attempts < ?",
        Long.class, maxAttempts);
    return count == null ? 0 : count;
  }

  /** Unpublished events that reached the attempt limit and need a manual replay. */
  public long countDead(int maxAttempts) {
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM parcel.outbox_event WHERE published_at IS NULL AND attempts >= ?",
        Long.class, maxAttempts);
    return count == null ? 0 : count;
  }

  /** When the oldest event still waiting to be published was written; empty when nothing is pending. */
  public Optional<OffsetDateTime> oldestPendingAt(int maxAttempts) {
    return Optional.ofNullable(jdbc.queryForObject(
        "SELECT MIN(occurred_at) FROM parcel.outbox_event WHERE published_at IS NULL AND attempts < ?",
        OffsetDateTime.class, maxAttempts));
  }
}
