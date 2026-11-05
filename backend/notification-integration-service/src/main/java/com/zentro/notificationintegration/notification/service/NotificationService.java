package com.zentro.notificationintegration.notification.service;

import com.zentro.notificationintegration.delivery.provider.NotificationSender;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.notification.entity.NotificationStatus;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.notification.template.NotificationMessages;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what to do with each parcel event. In one transaction per event it first marks the event as processed
 * (a duplicate stops here), then stores a SKIPPED row or a PENDING notification. Sending happens after the
 * commit (see {@link NotificationDispatcher}), so a rolled-back transaction can never have sent anything.
 */
@Service
public class NotificationService {
  public static final String CONSUMER = "notification-integration-service";
  static final String SKIPPED_MESSAGE_PREFIX = "No notification for status ";

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

  private final NotificationRepository repository;
  private final NotificationMessages messages;
  private final NotificationSender sender;
  private final NotificationMetrics metrics;
  private final Clock clock;

  public NotificationService(NotificationRepository repository, NotificationMessages messages,
      NotificationSender sender, NotificationMetrics metrics, Clock clock) {
    this.repository = repository;
    this.messages = messages;
    this.sender = sender;
    this.metrics = metrics;
    this.clock = clock;
  }

  /**
   * Stores the outcome for one valid event.
   *
   * @return the id of a new PENDING notification to send now; empty for duplicates and skipped statuses
   */
  @Transactional
  public Optional<UUID> handle(ParcelEvent event) {
    if (!repository.markProcessed(CONSUMER, event.eventId())) {
      metrics.duplicate();
      log.debug("Duplicate parcel event {} ignored", event.eventId());
      return Optional.empty();
    }
    Optional<String> message = messages.messageFor(event);
    if (message.isEmpty()) {
      repository.insertAttempt(event, sender.channel(), SKIPPED_MESSAGE_PREFIX + event.status(),
          NotificationStatus.SKIPPED, null);
      metrics.skipped();
      log.info("Notification skipped: eventId={} parcel {} status={}", event.eventId(), event.trackingRef(),
          event.status());
      return Optional.empty();
    }
    UUID id = repository.insertAttempt(event, sender.channel(), message.get(), NotificationStatus.PENDING,
        OffsetDateTime.now(clock));
    return Optional.of(id);
  }

  /** Records a malformed message by its position and a short reason; the content is never stored or logged. */
  @Transactional
  public void reject(String topic, Integer partition, Long offset, String reason) {
    repository.insertRejected(topic, partition, offset, reason);
    metrics.rejected();
    log.warn("Rejected malformed parcel event at {}-{}@{}: {}", topic, partition, offset, reason);
  }
}
