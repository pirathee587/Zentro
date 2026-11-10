package com.zentro.dispatchcourier.event;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "zentro.outbox")
public record OutboxProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("1000") long pollIntervalMs,
    @DefaultValue("50") int batchSize,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("5000") long sendTimeoutMs,
    @DefaultValue("1000") long retryBaseDelayMs,
    @DefaultValue("300000") long retryMaxDelayMs,
    Map<String, String> topics) {
  public OutboxProperties {
    if (batchSize < 1 || maxAttempts < 1) throw new IllegalArgumentException("Outbox limits must be positive");
    topics = topics == null ? Map.of() : Map.copyOf(topics);
  }
  public String topicFor(String eventType) { return topics.get(eventType); }
  public Duration retryDelay(int attempts) {
    long multiplier = 1L << Math.min(Math.max(attempts, 0), 30);
    long delay = retryBaseDelayMs * multiplier;
    return Duration.ofMillis(delay <= 0 ? retryMaxDelayMs : Math.min(delay, retryMaxDelayMs));
  }
}
