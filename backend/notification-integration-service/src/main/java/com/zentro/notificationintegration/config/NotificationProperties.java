package com.zentro.notificationintegration.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings under {@code zentro.notifications.*}. All values come from environment variables. */
@ConfigurationProperties(prefix = "zentro.notifications")
public record NotificationProperties(
    @DefaultValue Consumer consumer,
    @DefaultValue Retry retry,
    @DefaultValue Mock mock,
    @DefaultValue Health health
) {
  /** @param enabled Kafka listener and retry scheduler on/off; off by default */
  public record Consumer(@DefaultValue("false") boolean enabled) {}

  /**
   * @param maxAttempts    send attempts before a notification is FAILED
   * @param pollIntervalMs pause between retry rounds
   * @param baseDelayMs    delay after the first failed attempt; doubles with every further failure
   * @param maxDelayMs     upper limit for the retry delay
   */
  public record Retry(
      @DefaultValue("5") int maxAttempts,
      @DefaultValue("5000") long pollIntervalMs,
      @DefaultValue("5000") long baseDelayMs,
      @DefaultValue("600000") long maxDelayMs) {
    public Retry {
      if (maxAttempts < 1) {
        throw new IllegalArgumentException("zentro.notifications.retry.max-attempts must be at least 1");
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

  /** @param failMode makes the mock sender fail on purpose, to test retries locally */
  public record Mock(@DefaultValue("none") FailMode failMode) {}

  /**
   * @param failedWindowMinutes FAILED notifications within this window turn health DOWN
   * @param stuckPendingMinutes a pending notification older than this turns health DOWN
   */
  public record Health(
      @DefaultValue("60") long failedWindowMinutes,
      @DefaultValue("10") long stuckPendingMinutes) {}

  public enum FailMode {
    NONE,
    ALWAYS,
    FIRST_ATTEMPT
  }
}
