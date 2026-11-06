package com.zentro.notificationintegration.webhook.config;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.service.WebhookDeliveryService;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health component "webhooks". DOWN when a delivery FAILED within the last {@code health.failed-window-minutes}
 * (default 60), or an open delivery is overdue by more than {@code health.stuck-minutes} (default 10, meaning the
 * retry scheduler is not keeping up). Deliveries closed because the merchant disabled the endpoint do not count.
 * Not part of the liveness/readiness groups, so failing merchant endpoints never restart the service.
 */
@Component(WebhookHealthIndicator.NAME) // the bean name is the health component name
public class WebhookHealthIndicator implements HealthIndicator {
  public static final String NAME = "webhooks";

  private final WebhookDeliveryRepository repository;
  private final WebhookProperties properties;
  private final Clock clock;

  public WebhookHealthIndicator(WebhookDeliveryRepository repository, WebhookProperties properties, Clock clock) {
    this.repository = repository;
    this.properties = properties;
    this.clock = clock;
  }

  @Override
  public Health health() {
    try {
      WebhookProperties.Health settings = properties.health();
      OffsetDateTime now = OffsetDateTime.now(clock);
      long pending = repository.countOpen();
      long failedRecently = repository.countFailedSince(now.minusMinutes(settings.failedWindowMinutes()),
          WebhookDeliveryService.DISABLED_ERROR);
      Optional<OffsetDateTime> oldestDue = repository.oldestDueAt();
      long overdueSeconds = oldestDue.map(at -> Math.max(0, Duration.between(at, now).toSeconds())).orElse(0L);
      boolean stuck = overdueSeconds > settings.stuckMinutes() * 60;

      Health.Builder builder = failedRecently > 0 || stuck ? Health.down() : Health.up();
      builder.withDetail("enabled", properties.enabled())
          .withDetail("pending", pending)
          .withDetail("failedRecently", failedRecently)
          .withDetail("oldestOverdueSeconds", overdueSeconds);
      if (failedRecently > 0) {
        builder.withDetail("reason", "webhook deliveries failed permanently in the last "
            + settings.failedWindowMinutes() + " minutes");
      } else if (stuck) {
        builder.withDetail("reason", "webhook deliveries are overdue by more than "
            + settings.stuckMinutes() + " minutes");
      }
      return builder.build();
    } catch (RuntimeException ex) {
      return Health.down().withDetail("error", ex.getClass().getSimpleName()).build();
    }
  }
}
