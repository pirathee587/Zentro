package com.zentro.notificationintegration.webhook.service;

import com.zentro.security.observability.PropagationContext;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.webhook.client.WebhookHttpSender;
import com.zentro.notificationintegration.webhook.client.WebhookHttpSender.SignedRequest;
import com.zentro.notificationintegration.webhook.client.WebhookSendResult;
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.entity.WebhookSubscription;
import com.zentro.notificationintegration.webhook.event.MerchantParcelEvent;
import com.zentro.notificationintegration.webhook.exception.InvalidWebhookUrlException;
import com.zentro.notificationintegration.webhook.exception.UnresolvableWebhookHostException;
import com.zentro.notificationintegration.webhook.exception.WebhookSecretException;
import com.zentro.notificationintegration.webhook.exception.WebhooksUnavailableException;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository.DeliveryAttempt;
import com.zentro.notificationintegration.webhook.repository.WebhookSubscriptionRepository;
import com.zentro.notificationintegration.webhook.security.WebhookSecretCipher;
import com.zentro.notificationintegration.webhook.security.WebhookSigner;
import com.zentro.notificationintegration.webhook.service.WebhookRetryPolicy.Outcome;
import com.zentro.notificationintegration.webhook.validation.WebhookUrlValidator;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant webhook delivery.
 * <ul>
 *   <li>{@link #handle}: one transaction per event. Marks the event as processed for consumer
 *       {@value #CONSUMER} (a duplicate stops here), skips events without a merchant or without an active
 *       subscription, otherwise stores a PENDING delivery with the envelope exactly as received.</li>
 *   <li>{@link #attempt}: one attempt under a row lock (SKIP LOCKED, so instances never send the same delivery
 *       twice at once). Checks the endpoint again, signs, sends, and records the response code and outcome.
 *       A later event for the same parcel waits while an earlier one is still open.</li>
 * </ul>
 * Logs carry safe event identifiers, status codes and bounded outcome categories: never merchant ids,
 * error descriptions, secrets, signatures or payloads.
 */
@Service
public class WebhookDeliveryService {
  private PropagationContext propagation = PropagationContext.noop();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPropagation(PropagationContext propagation) { this.propagation = propagation; }

  public static final String CONSUMER = "merchant-webhooks";
  /** last_error of deliveries closed because the merchant disabled the endpoint (not a delivery failure). */
  public static final String DISABLED_ERROR = "Subscription disabled";

  private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

  private final NotificationRepository processedEvents;
  private final WebhookSubscriptionRepository subscriptions;
  private final WebhookDeliveryRepository deliveries;
  private final WebhookUrlValidator validator;
  private final WebhookSecretCipher cipher;
  private final WebhookSigner signer;
  private final WebhookHttpSender sender;
  private final WebhookRetryPolicy policy;
  private final WebhookMetrics metrics;
  private final Duration holdBack;
  private final Clock clock;

  public WebhookDeliveryService(NotificationRepository processedEvents, WebhookSubscriptionRepository subscriptions,
      WebhookDeliveryRepository deliveries, WebhookUrlValidator validator, WebhookSecretCipher cipher,
      WebhookSigner signer, WebhookHttpSender sender, WebhookRetryPolicy policy, WebhookMetrics metrics,
      WebhookProperties properties, Clock clock) {
    this.processedEvents = processedEvents;
    this.subscriptions = subscriptions;
    this.deliveries = deliveries;
    this.validator = validator;
    this.cipher = cipher;
    this.signer = signer;
    this.sender = sender;
    this.policy = policy;
    this.metrics = metrics;
    this.holdBack = Duration.ofMillis(properties.retry().baseDelayMs());
    this.clock = clock;
  }

  /**
   * Stores the delivery for one valid event.
   *
   * @return the id of a new PENDING delivery to attempt now; empty for duplicates and skipped events
   */
  @Transactional
  public Optional<UUID> handle(MerchantParcelEvent event) {
    UUID eventId = event.event().eventId();
    if (!processedEvents.markProcessed(CONSUMER, eventId)) {
      metrics.duplicate();
      log.debug("Duplicate parcel event ignored for webhooks: eventId={}", eventId);
      return Optional.empty();
    }
    if (event.merchantId() == null) {
      metrics.skipped();
      log.debug("No webhook for a customer parcel: eventId={}", eventId);
      return Optional.empty();
    }
    Optional<WebhookSubscription> subscription = subscriptions.findActiveByMerchant(event.merchantId());
    if (subscription.isEmpty()) {
      metrics.skipped();
      log.debug("No active webhook endpoint: eventId={}", eventId);
      return Optional.empty();
    }
    Optional<UUID> id = deliveries.insertPending(subscription.get().id(), eventId, event.event().eventType(),
        event.event().parcelId(), event.rawEnvelope(), sha256(event.rawEnvelope()), OffsetDateTime.now(clock));
    id.ifPresent(d -> log.info("Webhook delivery queued: eventId={} deliveryId={}", eventId,
        d));
    return id;
  }

  /** Records a malformed message by its position and a short reason; the content is never stored or logged. */
  @Transactional
  public void reject(String topic, Integer partition, Long offset, String reason) {
    processedEvents.insertRejected(topic, partition, offset, CONSUMER + ": " + reason);
    metrics.rejected();
    log.warn("Rejected malformed parcel event for webhooks at {}-{}@{}: {}", topic, partition, offset, reason);
  }

  /** Makes one attempt. Does nothing when the delivery is already final or being sent by another instance. */
  @Transactional
  public void attempt(UUID deliveryId) {
    DeliveryAttempt row = deliveries.lockForAttempt(deliveryId).orElse(null);
    if (row == null) {
      return;
    }
    try (var work = propagation.start(row.propagation(), "webhook.deliver", null); var scope = work.scope()) {
      OffsetDateTime now = OffsetDateTime.now(clock);
      if (!row.subscriptionActive()) {
        deliveries.markFailed(row.id(), row.attempts(), null, DISABLED_ERROR);
        metrics.skipped();
        metrics.recordTerminalFailure(WebhookTerminalCategory.SUBSCRIPTION_DISABLED);
        log.info("Webhook delivery closed, endpoint disabled: eventId={} terminalCategory={}",
            row.eventId(), WebhookTerminalCategory.SUBSCRIPTION_DISABLED);
        return;
      }
      if (deliveries.hasEarlierOpen(row.subscriptionId(), row.parcelId(), row.createdAt(), row.id())) {
        deliveries.postpone(row.id(), now.plus(holdBack));
        log.debug("Webhook delivery held back for parcel order: eventId={}", row.eventId());
        return;
      }

      int attempt = row.attempts() + 1;
      URI url;
      try {
        url = validator.validate(row.url());
      } catch (UnresolvableWebhookHostException ex) {
        work.failed();
        record(row, attempt, WebhookSendResult.networkError("host cannot be resolved"));
        return;
      } catch (InvalidWebhookUrlException ex) {
        work.failed();
        fail(row, attempt, null, "Endpoint not allowed: " + ex.getMessage(),
            WebhookResponseClass.NON_RETRYABLE_FAILURE, WebhookTerminalCategory.ENDPOINT_NOT_ALLOWED);
        return;
      }
      String secret;
      try {
        secret = cipher.decrypt(row.secretCiphertext(), row.merchantId());
      } catch (WebhookSecretException | WebhooksUnavailableException ex) {
        work.failed();
        fail(row, attempt, null, "Signing secret unavailable",
            WebhookResponseClass.NON_RETRYABLE_FAILURE, WebhookTerminalCategory.SECRET_UNAVAILABLE);
        return;
      }

      long timestamp = Instant.now(clock).getEpochSecond();
      WebhookSendResult result = sender.send(new SignedRequest(url, row.eventId().toString(), row.eventType(),
          timestamp, signer.sign(secret, timestamp, row.payload()), row.payload()));
      if (result.status() == null || result.status() >= 300) work.failed();
      record(row, attempt, result);
    }
  }

  private void record(DeliveryAttempt row, int attempt, WebhookSendResult result) {
    Outcome outcome = policy.classify(result, attempt);
    metrics.recordAttempt(outcome.responseClass(), outcome.decision());
    switch (outcome.decision()) {
      case SUCCEEDED -> {
        deliveries.markSucceeded(row.id(), attempt, result.status());
        metrics.succeeded();
        log.info("Webhook delivered: eventId={} status={} responseClass={} attempts={}",
            row.eventId(), result.status(), outcome.responseClass(), attempt);
      }
      case RETRY -> {
        OffsetDateTime next = OffsetDateTime.now(clock).plus(outcome.delay());
        deliveries.markRetry(row.id(), attempt, result.status(), outcome.error(), next);
        metrics.retried();
        log.warn("Webhook attempt failed, will retry: eventId={} status={} responseClass={} attempts={}"
            + " nextTry={}", row.eventId(), result.status(), outcome.responseClass(), attempt, next);
      }
      case FAILED -> {
        WebhookTerminalCategory category = outcome.error() != null && outcome.error().contains("gave up after")
            ? WebhookTerminalCategory.EXHAUSTED_RETRIES
            : WebhookTerminalCategory.NON_RETRYABLE_STATUS;
        fail(row, attempt, result.status(), outcome.error(), outcome.responseClass(), category);
      }
    }
  }

  private void fail(DeliveryAttempt row, int attempt, Integer status, String error,
      WebhookResponseClass responseClass, WebhookTerminalCategory category) {
    deliveries.markFailed(row.id(), attempt, status, error);
    metrics.failed();
    metrics.recordTerminalFailure(category);
    log.error("Webhook delivery failed permanently: eventId={} status={} responseClass={} terminalCategory={} attempts={}",
        row.eventId(), status, responseClass, category, attempt);
  }

  static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is not available", ex);
    }
  }
}
