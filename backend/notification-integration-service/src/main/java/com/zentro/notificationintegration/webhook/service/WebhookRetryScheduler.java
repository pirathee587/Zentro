package com.zentro.notificationintegration.webhook.service;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-attempts webhook deliveries whose next attempt is due: RETRY rows (408, 429, 5xx, timeouts, network errors),
 * deliveries held back for parcel order, and PENDING rows left behind by a crash between storing and sending.
 * Each row is locked with SKIP LOCKED, so several instances can run this safely. Active only when webhooks are
 * enabled.
 */
@Component
@ConditionalOnProperty(prefix = "zentro.webhooks", name = "enabled", havingValue = "true")
public class WebhookRetryScheduler {
  static final int BATCH_SIZE = 50;
  private static final Logger log = LoggerFactory.getLogger(WebhookRetryScheduler.class);

  private final WebhookDeliveryRepository repository;
  private final WebhookDeliveryService service;
  private final Clock clock;

  public WebhookRetryScheduler(WebhookDeliveryRepository repository, WebhookDeliveryService service, Clock clock) {
    this.repository = repository;
    this.service = service;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${zentro.webhooks.retry.poll-interval-ms:5000}")
  public void retryDue() {
    List<UUID> due;
    try {
      due = repository.findDue(BATCH_SIZE, OffsetDateTime.now(clock));
    } catch (RuntimeException ex) {
      log.warn("Webhook retry round skipped: {}", ex.getClass().getSimpleName());
      return;
    }
    for (UUID id : due) {
      try {
        service.attempt(id);
      } catch (RuntimeException ex) {
        log.warn("Webhook retry deferred: deliveryId={} category={}", id, ex.getClass().getSimpleName());
      }
    }
  }
}
