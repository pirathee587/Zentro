package com.zentro.parcelpricing.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for publishing outbox events to Kafka ({@code zentro.outbox.*}).
 *
 * @param enabled           publisher on/off; off by default so nothing needs a broker
 * @param pollIntervalMs    pause between publishing rounds
 * @param batchSize         events read per round
 * @param maxAttempts       failed attempts after which an event is "dead" and no longer retried
 * @param sendTimeoutMs     how long to wait for the broker to confirm one event
 * @param retryBaseDelayMs  first retry delay; doubles with every failed attempt
 * @param retryMaxDelayMs   upper limit for the retry delay
 * @param stuckThresholdMs  oldest pending event age after which health reports DOWN
 * @param topicPartitions   partitions for topics created at startup (local runs)
 * @param topicReplicas     replication factor for topics created at startup (local runs)
 * @param topics            outbox event_type -> Kafka topic
 */
@ConfigurationProperties(prefix = "zentro.outbox")
public record OutboxProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("1000") long pollIntervalMs,
    @DefaultValue("50") int batchSize,
    @DefaultValue("10") int maxAttempts,
    @DefaultValue("5000") long sendTimeoutMs,
    @DefaultValue("1000") long retryBaseDelayMs,
    @DefaultValue("300000") long retryMaxDelayMs,
    @DefaultValue("300000") long stuckThresholdMs,
    @DefaultValue("3") int topicPartitions,
    @DefaultValue("1") short topicReplicas,
    Map<String, String> topics
) {
  public OutboxProperties {
    if (batchSize < 1) {
      throw new IllegalArgumentException("zentro.outbox.batch-size must be at least 1");
    }
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("zentro.outbox.max-attempts must be at least 1");
    }
    topics = topics == null ? Map.of() : Map.copyOf(topics);
  }

  /** Topic for an outbox event type, or null when the type is not mapped. */
  public String topicFor(String eventType) {
    return topics.get(eventType);
  }

  /** Delay before the next try after {@code attemptsSoFar} failed attempts: base * 2^attempts, capped. */
  public Duration retryDelay(int attemptsSoFar) {
    int exponent = Math.max(0, Math.min(attemptsSoFar, 30));
    long delay = retryBaseDelayMs * (1L << exponent);
    if (delay <= 0 || delay > retryMaxDelayMs) {
      delay = retryMaxDelayMs;
    }
    return Duration.ofMillis(delay);
  }
}
