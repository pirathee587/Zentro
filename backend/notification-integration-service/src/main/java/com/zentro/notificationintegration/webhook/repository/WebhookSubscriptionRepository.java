package com.zentro.notificationintegration.webhook.repository;

import com.zentro.notificationintegration.webhook.entity.WebhookSubscription;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** JdbcTemplate access to notification.webhook_subscription. Every query is scoped to one merchant. */
@Repository
public class WebhookSubscriptionRepository {
  private static final String COLUMNS =
      "id, merchant_id, url, secret_ciphertext, secret_prefix, active, created_at, updated_at";
  private static final RowMapper<WebhookSubscription> ROW_MAPPER = (rs, n) -> new WebhookSubscription(
      rs.getObject("id", UUID.class),
      rs.getString("merchant_id"),
      rs.getString("url"),
      rs.getString("secret_ciphertext"),
      rs.getString("secret_prefix"),
      rs.getBoolean("active"),
      rs.getObject("created_at", OffsetDateTime.class),
      rs.getObject("updated_at", OffsetDateTime.class));

  private final JdbcTemplate jdbc;

  public WebhookSubscriptionRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<WebhookSubscription> findByMerchant(String merchantId) {
    return jdbc.query("SELECT " + COLUMNS + " FROM notification.webhook_subscription WHERE merchant_id = ?",
        ROW_MAPPER, merchantId).stream().findFirst();
  }

  public Optional<WebhookSubscription> findActiveByMerchant(String merchantId) {
    return jdbc.query("SELECT " + COLUMNS
            + " FROM notification.webhook_subscription WHERE merchant_id = ? AND active",
        ROW_MAPPER, merchantId).stream().findFirst();
  }

  /**
   * Creates the merchant's subscription, or replaces its URL and secret and makes it active again. The unique
   * merchant_id keeps one endpoint per merchant even when two requests race.
   */
  public WebhookSubscription upsert(String merchantId, String url, String secretCiphertext, String secretPrefix) {
    return jdbc.queryForObject("""
        INSERT INTO notification.webhook_subscription (merchant_id, url, secret_ciphertext, secret_prefix)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (merchant_id) DO UPDATE
           SET url = EXCLUDED.url,
               secret_ciphertext = EXCLUDED.secret_ciphertext,
               secret_prefix = EXCLUDED.secret_prefix,
               active = true,
               updated_at = now()
        RETURNING\s""" + COLUMNS, ROW_MAPPER, merchantId, url, secretCiphertext, secretPrefix);
  }

  /** Replaces the secret of an active subscription; empty when the merchant has no active subscription. */
  public Optional<WebhookSubscription> updateSecret(String merchantId, String secretCiphertext, String secretPrefix) {
    return jdbc.query("""
        UPDATE notification.webhook_subscription
           SET secret_ciphertext = ?, secret_prefix = ?, updated_at = now()
         WHERE merchant_id = ? AND active
        RETURNING\s""" + COLUMNS, ROW_MAPPER, secretCiphertext, secretPrefix, merchantId).stream().findFirst();
  }

  /** Disables the merchant's subscription. Returns false when the merchant never registered one. */
  public boolean deactivate(String merchantId) {
    return jdbc.update("""
        UPDATE notification.webhook_subscription
           SET active = false, updated_at = CASE WHEN active THEN now() ELSE updated_at END
         WHERE merchant_id = ?
        """, merchantId) == 1;
  }
}
