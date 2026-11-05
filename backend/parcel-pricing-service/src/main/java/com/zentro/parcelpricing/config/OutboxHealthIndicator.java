package com.zentro.parcelpricing.config;

import com.zentro.parcelpricing.outbox.repository.OutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Health component "outbox". UP with the pending and dead counts; DOWN when any event is dead or the oldest
 * pending event is older than {@code zentro.outbox.stuck-threshold-ms}. Kubernetes liveness/readiness groups do
 * not include it, so a stuck broker never restarts the service. Actuator exposure is unchanged.
 */
@Component
@ConditionalOnProperty(prefix = "zentro.outbox", name = "enabled", havingValue = "true")
public class OutboxHealthIndicator implements HealthIndicator {
  private final OutboxRepository repository;
  private final OutboxProperties properties;
  private final Clock clock;

  @Autowired
  public OutboxHealthIndicator(OutboxRepository repository, OutboxProperties properties) {
    this(repository, properties, Clock.systemUTC());
  }

  OutboxHealthIndicator(OutboxRepository repository, OutboxProperties properties, Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public Health health() {
    try {
      int maxAttempts = properties.maxAttempts();
      long pending = repository.countPending(maxAttempts);
      long dead = repository.countDead(maxAttempts);
      Optional<OffsetDateTime> oldest = repository.oldestPendingAt(maxAttempts);
      long oldestAgeSeconds = oldest
          .map(at -> Math.max(0, Duration.between(at, OffsetDateTime.now(clock)).toSeconds()))
          .orElse(0L);
      boolean stuck = oldestAgeSeconds * 1000 > properties.stuckThresholdMs();

      Health.Builder builder = dead > 0 || stuck ? Health.down() : Health.up();
      builder.withDetail("pending", pending)
          .withDetail("dead", dead)
          .withDetail("oldestPendingAgeSeconds", oldestAgeSeconds);
      if (dead > 0) {
        builder.withDetail("reason", "events reached the attempt limit; replay them after fixing the cause");
      } else if (stuck) {
        builder.withDetail("reason", "pending events are older than the stuck threshold");
      }
      return builder.build();
    } catch (RuntimeException ex) {
      return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
    }
  }
}
