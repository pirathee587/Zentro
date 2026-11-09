package com.zentro.notificationintegration.notification.repository;

import com.zentro.security.observability.PropagationContext;
import com.zentro.security.observability.PropagationMetadata;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.notification.entity.NotificationStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate access to the notification schema. */
@Repository
public class NotificationRepository {
  private PropagationContext propagation = PropagationContext.noop();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPropagation(PropagationContext propagation) { this.propagation = propagation; }

  static final int MAX_ERROR_LENGTH = 500;
  static final int MAX_REASON_LENGTH = 200;
  static final int MAX_MESSAGE_LENGTH = 500;

  /** The parts of a notification row needed to (re)send it. */
  public record AttemptRow(UUID id, UUID eventId, String trackingRef, String parcelStatus, String channel,
      String message, int attempts, PropagationMetadata propagation) {
    public AttemptRow(UUID id, UUID eventId, String trackingRef, String parcelStatus, String channel, String message, int attempts) {
      this(id, eventId, trackingRef, parcelStatus, channel, message, attempts, new PropagationMetadata(eventId.toString(), null, null));
    }
    @Override public String toString() { return "AttemptRow[id=" + id + ", eventId=" + eventId + "]"; }
  }

  private final JdbcTemplate jdbc;

  public NotificationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Records that {@code consumer} handled {@code eventId}. Returns false when it was already recorded (a duplicate).
   * The primary key decides, so two consumers racing on the same event cannot both win.
   */
  public boolean markProcessed(String consumer, UUID eventId) {
    return jdbc.update("""
        INSERT INTO notification.processed_event (consumer, event_id) VALUES (?, ?)
        ON CONFLICT (consumer, event_id) DO NOTHING
        """, consumer, eventId) == 1;
  }

  /** Creates the notification row for an event and returns its id. */
  public UUID insertAttempt(ParcelEvent event, String channel, String message, NotificationStatus status,
      OffsetDateTime nextAttemptAt) {
    var metadata = propagation.capture();
    return jdbc.queryForObject("""
        INSERT INTO notification.notification_attempt
            (event_id, event_type, parcel_id, tracking_ref, parcel_status, channel, message, status, next_attempt_at, correlation_id, traceparent, tracestate)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING id
        """, UUID.class,
        event.eventId(), event.eventType(), event.parcelId(), event.trackingRef(), event.status(), channel,
        cut(message, MAX_MESSAGE_LENGTH), status.name(), nextAttemptAt, metadata.correlationId(), metadata.traceparent(), metadata.tracestate());
  }

  /**
   * Locks a notification that is waiting to be sent. Empty when it does not exist, is already final, or another
   * instance is sending it right now. Must run inside a transaction.
   */
  public Optional<AttemptRow> lockForDelivery(UUID id) {
    return jdbc.query("""
        SELECT id, event_id, tracking_ref, parcel_status, channel, message, attempts, correlation_id, traceparent, tracestate
          FROM notification.notification_attempt
         WHERE id = ? AND status IN ('PENDING', 'RETRY')
         FOR UPDATE SKIP LOCKED
        """,
        (rs, n) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
            rs.getString("tracking_ref"), rs.getString("parcel_status"), rs.getString("channel"),
            rs.getString("message"), rs.getInt("attempts"), new PropagationMetadata(
                rs.getString("correlation_id") == null ? rs.getString("event_id") : rs.getString("correlation_id"),
                rs.getString("traceparent"), rs.getString("tracestate"))),
        id).stream().findFirst();
  }

  /** Ids of notifications whose next attempt is due, oldest first. */
  public List<UUID> findDue(int limit, OffsetDateTime now) {
    return jdbc.queryForList("""
        SELECT id FROM notification.notification_attempt
         WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?
         ORDER BY next_attempt_at
         LIMIT ?
        """, UUID.class, now, limit);
  }

  public void markSent(UUID id, int attempts) {
    jdbc.update("""
        UPDATE notification.notification_attempt
           SET status = 'SENT', attempts = ?, next_attempt_at = NULL, last_error = NULL, updated_at = now()
         WHERE id = ?
        """, attempts, id);
  }

  public void markRetry(UUID id, int attempts, String error, OffsetDateTime nextAttemptAt) {
    jdbc.update("""
        UPDATE notification.notification_attempt
           SET status = 'RETRY', attempts = ?, last_error = ?, next_attempt_at = ?, updated_at = now()
         WHERE id = ?
        """, attempts, cut(error, MAX_ERROR_LENGTH), nextAttemptAt, id);
  }

  public void markFailed(UUID id, int attempts, String error) {
    jdbc.update("""
        UPDATE notification.notification_attempt
           SET status = 'FAILED', attempts = ?, last_error = ?, next_attempt_at = NULL, updated_at = now()
         WHERE id = ?
        """, attempts, cut(error, MAX_ERROR_LENGTH), id);
  }

  /** Records a malformed message by position and reason only, never its content. */
  public void insertRejected(String topic, Integer partition, Long offset, String reason) {
    jdbc.update("""
        INSERT INTO notification.rejected_event (topic, partition_no, offset_no, reason) VALUES (?, ?, ?, ?)
        """, topic, partition, offset, cut(reason, MAX_REASON_LENGTH));
  }

  /** Notifications still waiting to be sent (PENDING or RETRY). */
  public long countOpen() {
    return count("SELECT COUNT(*) FROM notification.notification_attempt WHERE status IN ('PENDING', 'RETRY')");
  }

  public long countFailed() {
    return count("SELECT COUNT(*) FROM notification.notification_attempt WHERE status = 'FAILED'");
  }

  public long countFailedSince(OffsetDateTime since) {
    Long n = jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification.notification_attempt WHERE status = 'FAILED' AND updated_at >= ?",
        Long.class, since);
    return n == null ? 0 : n;
  }

  /** When the oldest notification still waiting to be sent was created; empty when none is waiting. */
  public Optional<OffsetDateTime> oldestOpenCreatedAt() {
    return Optional.ofNullable(jdbc.queryForObject(
        "SELECT MIN(created_at) FROM notification.notification_attempt WHERE status IN ('PENDING', 'RETRY')",
        OffsetDateTime.class));
  }

  public List<AttemptHistoryRow> listHistory(int limit, long offset) {
    return jdbc.query("""
        SELECT id, event_id, parcel_id, event_type, tracking_ref, parcel_status, channel, status, attempts,
               last_error, next_attempt_at, created_at, updated_at
          FROM notification.notification_attempt
         ORDER BY created_at DESC, id
         LIMIT ? OFFSET ?
        """, (rs, n) -> new AttemptHistoryRow(
        rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
        rs.getObject("parcel_id", UUID.class), rs.getString("event_type"),
        rs.getString("tracking_ref"), rs.getString("parcel_status"), rs.getString("channel"),
        rs.getString("status"), rs.getInt("attempts"), rs.getString("last_error"),
        rs.getObject("next_attempt_at", OffsetDateTime.class),
        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)),
        limit, offset);
  }

  public long countHistory() {
    return count("SELECT COUNT(*) FROM notification.notification_attempt");
  }

  public record AttemptHistoryRow(UUID id, UUID eventId, UUID parcelId, String eventType, String trackingRef,
      String parcelStatus, String channel, String status, int attempts, String lastError,
      OffsetDateTime nextAttemptAt, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

  private long count(String sql) {
    Long n = jdbc.queryForObject(sql, Long.class);
    return n == null ? 0 : n;
  }

  private static String cut(String value, int max) {
    return value == null || value.length() <= max ? value : value.substring(0, max);
  }
}
