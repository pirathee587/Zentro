package com.zentro.notificationintegration.webhook.repository;

import com.zentro.notificationintegration.webhook.entity.WebhookDelivery;
import com.zentro.notificationintegration.webhook.entity.WebhookDeliveryStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate access to notification.webhook_delivery. */
@Repository
public class WebhookDeliveryRepository {
  static final int MAX_ERROR_LENGTH = 500;

  /**
   * Everything needed to make one attempt: the delivery, its stored payload and the subscription it goes to,
   * read under a row lock on the delivery.
   */
  public record DeliveryAttempt(
      UUID id,
      UUID subscriptionId,
      UUID eventId,
      String eventType,
      UUID parcelId,
      String payload,
      int attempts,
      OffsetDateTime createdAt,
      String merchantId,
      String url,
      String secretCiphertext,
      boolean subscriptionActive) {}

  private static final RowMapper<WebhookDelivery> ROW_MAPPER = (rs, n) -> new WebhookDelivery(
      rs.getObject("id", UUID.class),
      rs.getObject("subscription_id", UUID.class),
      rs.getObject("event_id", UUID.class),
      rs.getString("event_type"),
      rs.getObject("parcel_id", UUID.class),
      WebhookDeliveryStatus.valueOf(rs.getString("status")),
      rs.getInt("attempts"),
      rs.getObject("response_status", Integer.class),
      rs.getString("last_error"),
      rs.getObject("next_attempt_at", OffsetDateTime.class),
      rs.getObject("created_at", OffsetDateTime.class),
      rs.getObject("updated_at", OffsetDateTime.class));

  private final JdbcTemplate jdbc;

  public WebhookDeliveryRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Creates a PENDING delivery that is due at {@code nextAttemptAt}. Empty when this subscription already has a
   * delivery for the event (the unique key makes a duplicate a no-op).
   */
  public Optional<UUID> insertPending(UUID subscriptionId, UUID eventId, String eventType, UUID parcelId,
      String payload, String payloadSha256, OffsetDateTime nextAttemptAt) {
    return jdbc.queryForList("""
        INSERT INTO notification.webhook_delivery
            (subscription_id, event_id, event_type, parcel_id, payload, payload_sha256, status, next_attempt_at)
        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)
        ON CONFLICT (subscription_id, event_id) DO NOTHING
        RETURNING id
        """, UUID.class, subscriptionId, eventId, eventType, parcelId, payload, payloadSha256, nextAttemptAt)
        .stream().findFirst();
  }

  /**
   * Locks an open delivery for one attempt. Empty when it does not exist, is already final, or another instance
   * is sending it right now. Must run inside a transaction.
   */
  public Optional<DeliveryAttempt> lockForAttempt(UUID id) {
    return jdbc.query("""
        SELECT d.id, d.subscription_id, d.event_id, d.event_type, d.parcel_id, d.payload, d.attempts, d.created_at,
               s.merchant_id, s.url, s.secret_ciphertext, s.active
          FROM notification.webhook_delivery d
          JOIN notification.webhook_subscription s ON s.id = d.subscription_id
         WHERE d.id = ? AND d.status IN ('PENDING', 'RETRY')
         FOR UPDATE OF d SKIP LOCKED
        """,
        (rs, n) -> new DeliveryAttempt(
            rs.getObject("id", UUID.class),
            rs.getObject("subscription_id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getString("event_type"),
            rs.getObject("parcel_id", UUID.class),
            rs.getString("payload"),
            rs.getInt("attempts"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getString("merchant_id"),
            rs.getString("url"),
            rs.getString("secret_ciphertext"),
            rs.getBoolean("active")),
        id).stream().findFirst();
  }

  /**
   * True when an earlier delivery for the same subscription and parcel is still open. Events for one parcel are
   * then held back, so the merchant receives them in order.
   */
  public boolean hasEarlierOpen(UUID subscriptionId, UUID parcelId, OffsetDateTime createdAt, UUID id) {
    Boolean exists = jdbc.queryForObject("""
        SELECT EXISTS (
            SELECT 1 FROM notification.webhook_delivery
             WHERE subscription_id = ? AND parcel_id = ? AND id <> ?
               AND status IN ('PENDING', 'RETRY') AND created_at < ?)
        """, Boolean.class, subscriptionId, parcelId, id, createdAt);
    return Boolean.TRUE.equals(exists);
  }

  /** Ids of open deliveries whose next attempt is due, oldest first. */
  public List<UUID> findDue(int limit) {
    return jdbc.queryForList("""
        SELECT id FROM notification.webhook_delivery
         WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= now()
         ORDER BY next_attempt_at
         LIMIT ?
        """, UUID.class, limit);
  }

  /** Moves the next attempt to {@code nextAttemptAt} without counting an attempt (held back for ordering). */
  public void postpone(UUID id, OffsetDateTime nextAttemptAt) {
    jdbc.update("UPDATE notification.webhook_delivery SET next_attempt_at = ?, updated_at = now() WHERE id = ?",
        nextAttemptAt, id);
  }

  public void markSucceeded(UUID id, int attempts, int responseStatus) {
    jdbc.update("""
        UPDATE notification.webhook_delivery
           SET status = 'SUCCEEDED', attempts = ?, response_status = ?, last_error = NULL,
               next_attempt_at = NULL, updated_at = now()
         WHERE id = ?
        """, attempts, responseStatus, id);
  }

  public void markRetry(UUID id, int attempts, Integer responseStatus, String error, OffsetDateTime nextAttemptAt) {
    jdbc.update("""
        UPDATE notification.webhook_delivery
           SET status = 'RETRY', attempts = ?, response_status = ?, last_error = ?, next_attempt_at = ?,
               updated_at = now()
         WHERE id = ?
        """, attempts, responseStatus, cut(error), nextAttemptAt, id);
  }

  public void markFailed(UUID id, int attempts, Integer responseStatus, String error) {
    jdbc.update("""
        UPDATE notification.webhook_delivery
           SET status = 'FAILED', attempts = ?, response_status = ?, last_error = ?, next_attempt_at = NULL,
               updated_at = now()
         WHERE id = ?
        """, attempts, responseStatus, cut(error), id);
  }

  /** One page of a merchant's deliveries, newest first. Only rows of that merchant's subscription. */
  public List<WebhookDelivery> listByMerchant(String merchantId, int limit, long offset) {
    return jdbc.query("""
        SELECT d.id, d.subscription_id, d.event_id, d.event_type, d.parcel_id, d.status, d.attempts,
               d.response_status, d.last_error, d.next_attempt_at, d.created_at, d.updated_at
          FROM notification.webhook_delivery d
          JOIN notification.webhook_subscription s ON s.id = d.subscription_id
         WHERE s.merchant_id = ?
         ORDER BY d.created_at DESC, d.id
         LIMIT ? OFFSET ?
        """, ROW_MAPPER, merchantId, limit, offset);
  }

  public long countByMerchant(String merchantId) {
    Long n = jdbc.queryForObject("""
        SELECT COUNT(*) FROM notification.webhook_delivery d
          JOIN notification.webhook_subscription s ON s.id = d.subscription_id
         WHERE s.merchant_id = ?
        """, Long.class, merchantId);
    return n == null ? 0 : n;
  }

  /** Deliveries still waiting to be sent (PENDING or RETRY). */
  public long countOpen() {
    return count("SELECT COUNT(*) FROM notification.webhook_delivery WHERE status IN ('PENDING', 'RETRY')", null);
  }

  /** FAILED deliveries, excluding ones closed because the merchant disabled the endpoint. */
  public long countFailed(String disabledError) {
    return count("SELECT COUNT(*) FROM notification.webhook_delivery WHERE status = 'FAILED'"
        + " AND last_error IS DISTINCT FROM ?", disabledError);
  }

  /** FAILED deliveries changed since {@code since}, excluding ones closed because the endpoint was disabled. */
  public long countFailedSince(OffsetDateTime since, String disabledError) {
    Long n = jdbc.queryForObject("""
        SELECT COUNT(*) FROM notification.webhook_delivery
         WHERE status = 'FAILED' AND updated_at >= ? AND last_error IS DISTINCT FROM ?
        """, Long.class, since, disabledError);
    return n == null ? 0 : n;
  }

  /** The earliest due time among open deliveries; empty when none is open. */
  public Optional<OffsetDateTime> oldestDueAt() {
    return Optional.ofNullable(jdbc.queryForObject(
        "SELECT MIN(next_attempt_at) FROM notification.webhook_delivery WHERE status IN ('PENDING', 'RETRY')",
        OffsetDateTime.class));
  }

  private long count(String sql, String arg) {
    Long n = arg == null ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, arg);
    return n == null ? 0 : n;
  }

  private static String cut(String value) {
    return value == null || value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
  }
}
