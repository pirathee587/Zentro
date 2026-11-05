package com.zentro.notificationintegration.config;

import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component "notifications". DOWN when a notification FAILED within the last
 * {@code health.failed-window-minutes} (default 60) or the oldest notification still waiting to be sent is
 * older than {@code health.stuck-pending-minutes} (default 10). Not part of the Kubernetes liveness/readiness
 * groups, so a failing SMS channel never restarts the service. Actuator exposure is unchanged.
 */
@Component(NotificationHealthIndicator.NAME) // the bean name is the health component name
public class NotificationHealthIndicator implements HealthIndicator {
  public static final String NAME = "notifications";

  private final NotificationRepository repository;
  private final NotificationProperties.Health settings;
  private final Clock clock;

  public NotificationHealthIndicator(NotificationRepository repository, NotificationProperties properties,
      Clock clock) {
    this.repository = repository;
    this.settings = properties.health();
    this.clock = clock;
  }

  @Override
  public Health health() {
    try {
      OffsetDateTime now = OffsetDateTime.now(clock);
      long pending = repository.countOpen();
      long failedRecently = repository.countFailedSince(now.minusMinutes(settings.failedWindowMinutes()));
      Optional<OffsetDateTime> oldest = repository.oldestOpenCreatedAt();
      long oldestAgeSeconds = oldest.map(at -> Math.max(0, Duration.between(at, now).toSeconds())).orElse(0L);
      boolean stuck = oldestAgeSeconds > settings.stuckPendingMinutes() * 60;

      Health.Builder builder = failedRecently > 0 || stuck ? Health.down() : Health.up();
      builder.withDetail("pending", pending)
          .withDetail("failedRecently", failedRecently)
          .withDetail("oldestPendingAgeSeconds", oldestAgeSeconds);
      if (failedRecently > 0) {
        builder.withDetail("reason", "notifications failed after all attempts in the last "
            + settings.failedWindowMinutes() + " minutes");
      } else if (stuck) {
        builder.withDetail("reason", "notifications have been waiting longer than "
            + settings.stuckPendingMinutes() + " minutes");
      }
      return builder.build();
    } catch (RuntimeException ex) {
      return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
    }
  }
}
