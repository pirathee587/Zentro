package com.zentro.notificationintegration.notification.service;

import com.zentro.notificationintegration.delivery.provider.NotificationSender;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.notification.entity.NotificationStatus;
import com.zentro.notificationintegration.notification.preference.ChannelPreferenceService;
import com.zentro.notificationintegration.notification.recipient.RecipientResolver;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.notification.template.NotificationMessages;
import com.zentro.notificationintegration.notification.template.TemplateRenderResult;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records what to do with each parcel event. In one transaction per event it first marks the event as processed
 * (a duplicate stops here), resolves the recipient and channel preferences, then stores a SKIPPED row or a PENDING
 * notification. Sending happens after the commit (see {@link com.zentro.notificationintegration.delivery.service.NotificationDispatcher}),
 * so a rolled-back transaction can never have sent anything.
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
  private final RecipientResolver recipientResolver;
  private final ChannelPreferenceService channelPreferenceService;

  public NotificationService(NotificationRepository repository, NotificationMessages messages,
      NotificationSender sender, NotificationMetrics metrics, Clock clock) {
    this(repository, messages, sender, metrics, clock, new RecipientResolver(), new ChannelPreferenceService());
  }

  @Autowired
  public NotificationService(NotificationRepository repository, NotificationMessages messages,
      NotificationSender sender, NotificationMetrics metrics, Clock clock,
      RecipientResolver recipientResolver, ChannelPreferenceService channelPreferenceService) {
    this.repository = repository;
    this.messages = messages;
    this.sender = sender;
    this.metrics = metrics;
    this.clock = clock;
    this.recipientResolver = recipientResolver;
    this.channelPreferenceService = channelPreferenceService;
  }

  /**
   * Stores the outcome for one valid event using default channel preferences.
   *
   * @return the id of a new PENDING notification to send now; empty for duplicates and skipped statuses
   */
  @Transactional
  public Optional<UUID> handle(ParcelEvent event) {
    return handle(event, null);
  }

  /**
   * Stores the outcome for one valid event considering explicit channel preferences.
   *
   * @return the id of a new PENDING notification to send now; empty for duplicates and skipped statuses
   */
  @Transactional
  public Optional<UUID> handle(ParcelEvent event, List<String> channelPreferences) {
    if (event == null) {
      log.warn("Notification event skipped: category=null_event");
      return Optional.empty();
    }
    if (!repository.markProcessed(CONSUMER, event.eventId())) {
      metrics.duplicate();
      log.debug("Duplicate parcel event ignored");
      return Optional.empty();
    }

    var recipient = recipientResolver.resolve(event);
    if (recipient.isEmpty()) {
      repository.insertAttempt(event, sender.channel(), "Unresolved recipient", NotificationStatus.SKIPPED, null);
      metrics.skipped();
      log.warn("Notification skipped: eventType={} category=unresolved_recipient variable=trackingRef", event.eventType());
      return Optional.empty();
    }

    var decision = channelPreferenceService.resolveChannel(recipient.get(), channelPreferences);
    if (!decision.isDeliverable()) {
      String fallbackChannel = decision.channel() != null ? decision.channel() : sender.channel();
      repository.insertAttempt(event, fallbackChannel, "Channel preference not supported", NotificationStatus.SKIPPED, null);
      metrics.skipped();
      log.warn("Notification skipped: eventType={} category=unsupported_channel channel={}", event.eventType(), decision.channel());
      return Optional.empty();
    }

    String channel = decision.channel();
    TemplateRenderResult result = messages.render(event, channel);
    if (result.outcome() != TemplateRenderResult.Outcome.RENDERED) {
      String skipMessage = result.outcome() == TemplateRenderResult.Outcome.SKIPPED
          ? SKIPPED_MESSAGE_PREFIX + event.status() : "Notification template unavailable";
      if (result.outcome() == TemplateRenderResult.Outcome.VALIDATION_FAILURE) {
        log.warn("Notification template validation failed: eventType={} templateId={} variables={}",
            event.eventType(), result.templateId(), result.invalidVariables());
      } else if (result.outcome() == TemplateRenderResult.Outcome.UNSUPPORTED_EVENT) {
        log.warn("Unsupported notification event: templateId={}", result.templateId());
      }
      repository.insertAttempt(event, channel, skipMessage, NotificationStatus.SKIPPED, null);
      metrics.skipped();
      log.info("Notification skipped: eventType={} templateId={} category={}", event.eventType(), result.templateId(),
          result.outcome());
      return Optional.empty();
    }
    UUID id = repository.insertAttempt(event, channel, result.message(), NotificationStatus.PENDING,
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
