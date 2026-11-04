package com.zentro.parcelpricing.event;

import com.zentro.parcelpricing.repository.OutboxRepository;
import com.zentro.parcelpricing.config.OutboxProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Micrometer meters for outbox publishing:
 * counters {@code zentro.outbox.published} and {@code zentro.outbox.failed} (tag eventType),
 * gauges {@code zentro.outbox.pending} and {@code zentro.outbox.dead}.
 */
@Component
@ConditionalOnProperty(prefix = "zentro.outbox", name = "enabled", havingValue = "true")
public class OutboxMetrics {
  public static final String PUBLISHED = "zentro.outbox.published";
  public static final String FAILED = "zentro.outbox.failed";
  public static final String PENDING = "zentro.outbox.pending";
  public static final String DEAD = "zentro.outbox.dead";

  private final MeterRegistry registry;

  public OutboxMetrics(MeterRegistry registry, OutboxRepository repository, OutboxProperties properties) {
    this.registry = registry;
    int maxAttempts = properties.maxAttempts();
    Gauge.builder(PENDING, () -> safeCount(() -> repository.countPending(maxAttempts)))
        .description("Outbox events waiting to be published")
        .register(registry);
    Gauge.builder(DEAD, () -> safeCount(() -> repository.countDead(maxAttempts)))
        .description("Outbox events that reached the attempt limit")
        .register(registry);
  }

  public void published(String eventType) {
    registry.counter(PUBLISHED, "eventType", eventType).increment();
  }

  public void failed(String eventType) {
    registry.counter(FAILED, "eventType", eventType).increment();
  }

  /** A gauge must never throw; report "unknown" when the database cannot be read. */
  private static double safeCount(LongSupplier count) {
    try {
      return count.getAsLong();
    } catch (RuntimeException ex) {
      return Double.NaN;
    }
  }
}
