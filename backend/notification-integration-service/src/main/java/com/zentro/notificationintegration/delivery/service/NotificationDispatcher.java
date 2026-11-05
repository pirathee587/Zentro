package com.zentro.notificationintegration.delivery.service;

import com.zentro.notificationintegration.config.NotificationProperties;
import com.zentro.notificationintegration.delivery.provider.NotificationSender;
import com.zentro.notificationintegration.delivery.provider.OutgoingNotification;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.notification.repository.NotificationRepository.AttemptRow;
import com.zentro.notificationintegration.notification.service.NotificationMetrics;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes one delivery attempt for a stored notification: SENT on success; otherwise RETRY with exponential
 * back-off and a short error, or FAILED once the attempt limit is reached. Logs contain delivery categories only,
 * never recipient or rendered message data.
 */
@Service
public class NotificationDispatcher {
  private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

  private final NotificationRepository repository;
  private final NotificationSender sender;
  private final NotificationMetrics metrics;
  private final NotificationProperties.Retry retry;
  private final Clock clock;

  public NotificationDispatcher(NotificationRepository repository, NotificationSender sender,
      NotificationMetrics metrics, NotificationProperties properties, Clock clock) {
    this.repository = repository;
    this.sender = sender;
    this.metrics = metrics;
    this.retry = properties.retry();
    this.clock = clock;
  }

  /** Tries to send one notification. Does nothing when it is already final or being sent by another instance. */
  @Transactional
  public void deliver(UUID notificationId) {
    AttemptRow row = repository.lockForDelivery(notificationId).orElse(null);
    if (row == null) {
      return;
    }
    int attempt = row.attempts() + 1;
    try {
      sender.send(new OutgoingNotification(row.eventId(), row.trackingRef(), row.parcelStatus(), row.channel(),
          row.message(), attempt));
      repository.markSent(row.id(), attempt);
      metrics.sent();
    } catch (Exception ex) {
      String error = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
      if (attempt >= retry.maxAttempts()) {
        repository.markFailed(row.id(), attempt, error);
        metrics.failed();
        log.error("Notification delivery exhausted retries: channel={} status={} attempts={}",
            row.channel(), row.parcelStatus(), attempt);
      } else {
        OffsetDateTime next = OffsetDateTime.now(clock).plus(retry.delayAfter(attempt));
        repository.markRetry(row.id(), attempt, error, next);
        metrics.retried();
        log.warn("Notification delivery attempt failed: channel={} status={} category={} nextTry={}",
            row.channel(), row.parcelStatus(), ex.getClass().getSimpleName(), next);
      }
    }
  }
}
