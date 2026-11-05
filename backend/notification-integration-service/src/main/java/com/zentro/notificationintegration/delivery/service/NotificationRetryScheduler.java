package com.zentro.notificationintegration.delivery.service;

import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-sends notifications whose next attempt is due (RETRY rows, and PENDING rows left behind by a crash between
 * storing and sending). Retries never sleep inside the Kafka listener. Each row is locked with SKIP LOCKED, so
 * several instances can run this safely. Active only when the consumer is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "zentro.notifications.consumer", name = "enabled", havingValue = "true")
public class NotificationRetryScheduler {
  static final int BATCH_SIZE = 50;
  private static final Logger log = LoggerFactory.getLogger(NotificationRetryScheduler.class);

  private final NotificationRepository repository;
  private final NotificationDispatcher dispatcher;

  public NotificationRetryScheduler(NotificationRepository repository, NotificationDispatcher dispatcher) {
    this.repository = repository;
    this.dispatcher = dispatcher;
  }

  @Scheduled(fixedDelayString = "${zentro.notifications.retry.poll-interval-ms:5000}")
  public void retryDue() {
    List<UUID> due;
    try {
      due = repository.findDue(BATCH_SIZE);
    } catch (RuntimeException ex) {
      log.warn("Notification retry round skipped: {}", ex.getClass().getSimpleName());
      return;
    }
    for (UUID id : due) {
      try {
        dispatcher.deliver(id);
      } catch (RuntimeException ex) {
        log.warn("Notification {} could not be retried now: {}", id, ex.getClass().getSimpleName());
      }
    }
  }
}
