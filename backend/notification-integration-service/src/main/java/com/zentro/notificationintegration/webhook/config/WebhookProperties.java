package com.zentro.notificationintegration.webhook.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings under {@code zentro.webhooks.*}. All values come from environment variables.
 *
 * @param enabled            Kafka listener and retry scheduler for merchant webhooks on/off; off by default
 * @param allowInsecureLocal DEVELOPMENT ONLY: also accept http:// and loopback endpoints (localhost) for local
 *                           checks. Every other private, link-local or metadata address stays blocked.
 * @param secretKey          base64 of a 32-byte AES key used to encrypt signing secrets; required when enabled.
 *                           Never logged (see {@link #toString()}).
 * @param consumerGroup      Kafka consumer group of the webhook listener, separate from notifications
 */
@ConfigurationProperties(prefix = "zentro.webhooks")
public record WebhookProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("false") boolean allowInsecureLocal,
    @DefaultValue("") String secretKey,
    @DefaultValue("merchant-webhooks") String consumerGroup,
    @DefaultValue Retry retry,
    @DefaultValue Http http,
    @DefaultValue Health health
) {

  /**
   * @param maxAttempts    attempts before a delivery is FAILED
   * @param pollIntervalMs pause between retry rounds
   * @param baseDelayMs    delay after the first failed attempt; doubles with every further failure
   * @param maxDelayMs     upper limit for the retry delay
   */
  public record Retry(
      @DefaultValue("8") int maxAttempts,
      @DefaultValue("5000") long pollIntervalMs,
      @DefaultValue("10000") long baseDelayMs,
      @DefaultValue("1800000") long maxDelayMs) {
    public Retry {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("zentro.webhooks.retry.max-attempts must be at least 1");
      }
    }

    /** Delay after {@code failedAttempts} failures: base * 2^(failedAttempts - 1), capped at the maximum. */
    public Duration delayAfter(int failedAttempts) {
      int exponent = Math.max(0, Math.min(failedAttempts - 1, 30));
      long delay = baseDelayMs * (1L << exponent);
      if (delay <= 0 || delay > maxDelayMs) {
        delay = maxDelayMs;
      }
      return Duration.ofMillis(delay);
    }
  }

  /** Outbound HTTP limits for one attempt. */
  public record Http(
      @DefaultValue("2000") long connectTimeoutMs,
      @DefaultValue("5000") long readTimeoutMs) {}

  /**
   * @param failedWindowMinutes a FAILED delivery within this window turns health DOWN
   * @param stuckMinutes        an open delivery overdue by more than this turns health DOWN
   */
  public record Health(
      @DefaultValue("60") long failedWindowMinutes,
      @DefaultValue("10") long stuckMinutes) {}

  /** Keeps the encryption key out of logs and error messages. */
  @Override
  public String toString() {
    return "WebhookProperties[enabled=" + enabled + ", allowInsecureLocal=" + allowInsecureLocal
        + ", secretKey=" + (secretKey == null || secretKey.isBlank() ? "<not set>" : "<set>")
        + ", consumerGroup=" + consumerGroup + ", retry=" + retry + ", http=" + http + ", health=" + health + "]";
  }
}
