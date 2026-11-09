package com.zentro.notificationintegration.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.webhook.client.WebhookHttpSender;
import com.zentro.notificationintegration.webhook.client.WebhookHttpSender.SignedRequest;
import com.zentro.notificationintegration.webhook.client.WebhookSendResult;
import com.zentro.notificationintegration.webhook.config.WebhookHealthIndicator;
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.entity.WebhookSubscription;
import com.zentro.notificationintegration.webhook.event.MerchantParcelEvent;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository.DeliveryAttempt;
import com.zentro.notificationintegration.webhook.repository.WebhookSubscriptionRepository;
import com.zentro.notificationintegration.webhook.security.WebhookSecretCipher;
import com.zentro.notificationintegration.webhook.security.WebhookSigner;
import com.zentro.notificationintegration.webhook.service.WebhookRetryPolicy.Decision;
import com.zentro.notificationintegration.webhook.service.WebhookRetryPolicy.Outcome;
import com.zentro.notificationintegration.webhook.validation.WebhookUrlValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Acceptance test suite for [ZD-37] Webhook retry testing (Issue #72).
 * Verifies timeouts, non-success responses, retry limits, idempotency, failure recording,
 * health observability, and credential exclusion from logs.
 */
class WebhookRetryTestingTest {
  private static final String ENCRYPTION_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  private static final Instant T0 = Instant.parse("2026-12-06T10:00:00Z");
  private static final String MERCHANT_ID = "merchant-acme-corp";
  private static final String SECRET_KEY = "whsec_super-confidential-merchant-secret-999";
  private static final String PAYLOAD_BODY =
      "{\"eventId\":\"evt-zd37\",\"payload\":{\"trackingRef\":\"ZT-ZD37TEST88\",\"status\":\"OUT_FOR_DELIVERY\"}}";

  private final NotificationRepository processedEvents = mock(NotificationRepository.class);
  private final WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final WebhookHttpSender sender = mock(WebhookHttpSender.class);

  private final WebhookProperties properties = new WebhookProperties(
      true,
      false,
      ENCRYPTION_KEY,
      "merchant-webhooks",
      new WebhookProperties.Retry(8, 5000, 10000, 1800000), // 8 max attempts, base 10s, cap 30m
      new WebhookProperties.Http(2000, 5000),
      new WebhookProperties.Health(60, 10));

  private final WebhookSecretCipher cipher = new WebhookSecretCipher(properties);
  private final WebhookSigner signer = new WebhookSigner();
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final WebhookMetrics metrics = new WebhookMetrics(meterRegistry, deliveries);
  private final WebhookRetryPolicy retryPolicy = new WebhookRetryPolicy(properties);
  private final WebhookUrlValidator urlValidator = new WebhookUrlValidator(false, WebhookRetryTestingTest::resolveHost);
  private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

  private final WebhookDeliveryService deliveryService = new WebhookDeliveryService(
      processedEvents,
      subscriptions,
      deliveries,
      urlValidator,
      cipher,
      signer,
      sender,
      retryPolicy,
      metrics,
      properties,
      clock);

  private final UUID deliveryId = UUID.randomUUID();
  private final UUID eventId = UUID.randomUUID();
  private final UUID parcelId = UUID.randomUUID();
  private final UUID subscriptionId = UUID.randomUUID();

  private ListAppender<ILoggingEvent> capturedLogs;

  private static InetAddress[] resolveHost(String host) throws UnknownHostException {
    return switch (host) {
      case "webhook.acme.com" -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
      case "blocked-internal.acme.com" -> new InetAddress[] {InetAddress.getByName("127.0.0.1")};
      default -> throw new UnknownHostException(host);
    };
  }

  @BeforeEach
  void attachLogAppender() {
    capturedLogs = new ListAppender<>();
    capturedLogs.start();
    ((Logger) LoggerFactory.getLogger(WebhookDeliveryService.class)).addAppender(capturedLogs);
  }

  @AfterEach
  void detachLogAppender() {
    ((Logger) LoggerFactory.getLogger(WebhookDeliveryService.class)).detachAppender(capturedLogs);
  }

  private MerchantParcelEvent createEvent(String merchantId) {
    return new MerchantParcelEvent(
        new ParcelEvent(
            eventId,
            "parcel.status.updated",
            parcelId,
            OffsetDateTime.parse("2026-12-06T09:59:00Z"),
            "ZT-ZD37TEST88",
            "OUT_FOR_DELIVERY",
            null),
        merchantId,
        PAYLOAD_BODY);
  }

  private DeliveryAttempt createAttemptRow(String endpointUrl, boolean active, int priorAttempts) {
    return createAttemptRow(deliveryId, endpointUrl, active, priorAttempts);
  }

  private DeliveryAttempt createAttemptRow(UUID id, String endpointUrl, boolean active, int priorAttempts) {
    return new DeliveryAttempt(
        id,
        subscriptionId,
        eventId,
        "parcel.status.updated",
        parcelId,
        PAYLOAD_BODY,
        priorAttempts,
        OffsetDateTime.parse("2026-12-06T09:59:30Z"),
        MERCHANT_ID,
        endpointUrl,
        cipher.encrypt(SECRET_KEY, MERCHANT_ID),
        active);
  }

  private double metricCount(String counterName) {
    return meterRegistry.counter(counterName).count();
  }

  // =========================================================================
  // 1. Retry Schedule & Maximum Attempts Policy
  // =========================================================================
  @Nested
  @DisplayName("1. Retry schedule and policy enforcement")
  class RetryScheduleAndPolicyTests {

    @ParameterizedTest(name = "Attempt {0} produces delay of {1} seconds")
    @CsvSource({
      "1, 10",
      "2, 20",
      "3, 40",
      "4, 80",
      "5, 160",
      "6, 320",
      "7, 640"
    })
    @DisplayName("Exponential backoff follows 10s * 2^(attempt - 1)")
    void exponentialBackoffSchedule(int attempt, int expectedDelaySeconds) {
      Outcome outcome = retryPolicy.classify(WebhookSendResult.response(503), attempt);
      assertThat(outcome.decision()).isEqualTo(Decision.RETRY);
      assertThat(outcome.delay()).isEqualTo(Duration.ofSeconds(expectedDelaySeconds));
    }

    @Test
    @DisplayName("Exponential backoff is strictly capped at 30 minutes (1800s)")
    void backoffCapAt30Minutes() {
      WebhookProperties.Retry customRetry = new WebhookProperties.Retry(25, 5000, 10000, 1800000);
      assertThat(customRetry.delayAfter(8)).isEqualTo(Duration.ofSeconds(1280));
      assertThat(customRetry.delayAfter(9)).isEqualTo(Duration.ofSeconds(1800)); // 2560s capped to 1800s
      assertThat(customRetry.delayAfter(20)).isEqualTo(Duration.ofSeconds(1800));
    }

    @Test
    @DisplayName("Boundary: Attempt 7 retries, Attempt 8 marks FAILED with exhaustion notice")
    void maxAttemptsBoundaryAtAttempt8() {
      Outcome attempt7 = retryPolicy.classify(WebhookSendResult.response(500), 7);
      assertThat(attempt7.decision()).isEqualTo(Decision.RETRY);
      assertThat(attempt7.error()).isEqualTo("HTTP 500");

      Outcome attempt8 = retryPolicy.classify(WebhookSendResult.response(500), 8);
      assertThat(attempt8.decision()).isEqualTo(Decision.FAILED);
      assertThat(attempt8.error()).isEqualTo("HTTP 500 (gave up after 8 attempts)");
      assertThat(attempt8.delay()).isNull();

      Outcome attempt9 = retryPolicy.classify(WebhookSendResult.response(500), 9);
      assertThat(attempt9.decision()).isEqualTo(Decision.FAILED);
      assertThat(attempt9.error()).isEqualTo("HTTP 500 (gave up after 9 attempts)");
    }

    @Test
    @DisplayName("WebhookRetryScheduler queries due deliveries via application clock and triggers attempts")
    void retrySchedulerPicksDueDeliveries() {
      WebhookDeliveryRepository mockRepo = mock(WebhookDeliveryRepository.class);
      WebhookDeliveryService mockDelivery = mock(WebhookDeliveryService.class);
      OffsetDateTime now = OffsetDateTime.ofInstant(T0, ZoneOffset.UTC);
      UUID dueId1 = UUID.randomUUID();
      UUID dueId2 = UUID.randomUUID();

      when(mockRepo.findDue(WebhookRetryScheduler.BATCH_SIZE, now)).thenReturn(List.of(dueId1, dueId2));

      WebhookRetryScheduler scheduler = new WebhookRetryScheduler(mockRepo, mockDelivery, clock);
      scheduler.retryDue();

      verify(mockRepo).findDue(WebhookRetryScheduler.BATCH_SIZE, now);
      verify(mockDelivery).attempt(dueId1);
      verify(mockDelivery).attempt(dueId2);
    }
  }

  // =========================================================================
  // 2. HTTP Responses & Timeouts Classification
  // =========================================================================
  @Nested
  @DisplayName("2. Non-success responses, timeouts, and redirects")
  class StatusAndTimeoutClassificationTests {

    @ParameterizedTest(name = "HTTP 2xx: status {0} -> SUCCEEDED")
    @ValueSource(ints = {200, 201, 202, 204, 206})
    @DisplayName("Any 2xx response marks delivery SUCCEEDED")
    void successResponses(int statusCode) {
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 0)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(statusCode));

      deliveryService.attempt(deliveryId);

      verify(deliveries).markSucceeded(deliveryId, 1, statusCode);
      assertThat(metricCount(WebhookMetrics.SUCCEEDED)).isEqualTo(1);
      assertThat(metricCount(WebhookMetrics.FAILED)).isZero();
    }

    @ParameterizedTest(name = "Retryable status {0}")
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    @DisplayName("408, 429, and 5xx trigger RETRY with next_attempt_at scheduled")
    void retryableStatuses(int statusCode) {
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 1)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(statusCode));

      deliveryService.attempt(deliveryId);

      // Attempt 2 fails -> next attempt is attempt 3 after 20 seconds
      verify(deliveries).markRetry(
          deliveryId,
          2,
          statusCode,
          "HTTP " + statusCode,
          OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).plusSeconds(20));
      assertThat(metricCount(WebhookMetrics.RETRIED)).isEqualTo(1);
      assertThat(metricCount(WebhookMetrics.FAILED)).isZero();
    }

    @ParameterizedTest(name = "Permanent 4xx / 3xx status {0}")
    @ValueSource(ints = {400, 401, 403, 404, 405, 410, 413, 422, 301, 302, 307})
    @DisplayName("Non-retryable 4xx and redirects (3xx) fail immediately on first attempt")
    void permanentFailures(int statusCode) {
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 0)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(statusCode));

      deliveryService.attempt(deliveryId);

      verify(deliveries).markFailed(deliveryId, 1, statusCode, "HTTP " + statusCode);
      assertThat(metricCount(WebhookMetrics.FAILED)).isEqualTo(1);
      assertThat(metricCount(WebhookMetrics.RETRIED)).isZero();
    }

    @Test
    @DisplayName("Connect and read timeouts are classified as RETRY")
    void timeoutsAreRetried() {
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 0)));
      when(sender.send(any())).thenReturn(WebhookSendResult.timeout());

      deliveryService.attempt(deliveryId);

      verify(deliveries).markRetry(
          deliveryId,
          1,
          null,
          "timeout",
          OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).plusSeconds(10));
      assertThat(metricCount(WebhookMetrics.RETRIED)).isEqualTo(1);
    }

    @Test
    @DisplayName("Network connection errors (e.g. ConnectException) are classified as RETRY")
    void networkErrorsAreRetried() {
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 3)));
      when(sender.send(any())).thenReturn(WebhookSendResult.networkError("network error: ConnectException"));

      deliveryService.attempt(deliveryId);

      verify(deliveries).markRetry(
          deliveryId,
          4,
          null,
          "network error: ConnectException",
          OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).plusSeconds(80));
      assertThat(metricCount(WebhookMetrics.RETRIED)).isEqualTo(1);
    }
  }

  // =========================================================================
  // 3. Idempotency & Concurrency Safety
  // =========================================================================
  @Nested
  @DisplayName("3. Idempotency and duplicate prevention")
  class IdempotencyTests {

    @Test
    @DisplayName("Repeated event delivery with identical eventId is skipped without side effects")
    void duplicateEventIgnored() {
      MerchantParcelEvent event = createEvent(MERCHANT_ID);
      // First attempt: event processed successfully
      when(processedEvents.markProcessed("merchant-webhooks", eventId)).thenReturn(true);
      when(subscriptions.findActiveByMerchant(MERCHANT_ID)).thenReturn(Optional.of(new WebhookSubscription(
          subscriptionId, MERCHANT_ID, "https://webhook.acme.com/v1", "v1.0", "whsec_123", true, null, null)));
      when(deliveries.insertPending(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(deliveryId));

      Optional<UUID> firstResult = deliveryService.handle(event);
      assertThat(firstResult).contains(deliveryId);

      // Duplicate delivery attempt: event already processed
      when(processedEvents.markProcessed("merchant-webhooks", eventId)).thenReturn(false);

      Optional<UUID> secondResult = deliveryService.handle(event);
      assertThat(secondResult).isEmpty();

      // Ensure no further subscriptions or delivery entries were touched
      verify(subscriptions, times(1)).findActiveByMerchant(anyString());
      verify(deliveries, times(1)).insertPending(any(), any(), any(), any(), any(), any(), any());
      assertThat(metricCount(WebhookMetrics.DUPLICATES)).isEqualTo(1);
    }

    @Test
    @DisplayName("Lock contention during attempt: skipped safely when row cannot be locked")
    void concurrentAttemptLockedSkipped() {
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.empty());

      deliveryService.attempt(deliveryId);

      verifyNoInteractions(sender);
      verify(deliveries, never()).markSucceeded(any(), anyInt(), anyInt());
      verify(deliveries, never()).markRetry(any(), anyInt(), any(), any(), any());
      verify(deliveries, never()).markFailed(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("In-flight ordering: delivery postponed if an earlier open delivery exists for parcel")
    void inFlightOrderPostponed() {
      DeliveryAttempt row = createAttemptRow("https://webhook.acme.com/v1", true, 0);
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(row));
      when(deliveries.hasEarlierOpen(subscriptionId, parcelId, row.createdAt(), deliveryId)).thenReturn(true);

      deliveryService.attempt(deliveryId);

      verifyNoInteractions(sender);
      verify(deliveries).postpone(deliveryId, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).plusSeconds(10));
      verify(deliveries, never()).markRetry(any(), anyInt(), any(), any(), any());
    }
  }

  // =========================================================================
  // 4. Failure, Recovery and Exhaustion Lifecycle
  // =========================================================================
  @Nested
  @DisplayName("4. Failure, recovery, and final failure exhaustion")
  class FailureRecoveryLifecycleTests {

    @Test
    @DisplayName("Recovery lifecycle: 503 -> timeout -> HTTP 200 (recovered and succeeded)")
    void failureAndRecoveryFlow() {
      // Step 1: Attempt 1 fails with HTTP 503
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 0)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(503));
      deliveryService.attempt(deliveryId);

      verify(deliveries).markRetry(deliveryId, 1, 503, "HTTP 503",
          OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).plusSeconds(10));
      assertThat(metricCount(WebhookMetrics.RETRIED)).isEqualTo(1);

      // Step 2: Attempt 2 fails with timeout
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 1)));
      when(sender.send(any())).thenReturn(WebhookSendResult.timeout());
      deliveryService.attempt(deliveryId);

      verify(deliveries).markRetry(deliveryId, 2, null, "timeout",
          OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).plusSeconds(20));
      assertThat(metricCount(WebhookMetrics.RETRIED)).isEqualTo(2);

      // Step 3: Attempt 3 recovers with HTTP 200
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 2)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(200));
      deliveryService.attempt(deliveryId);

      verify(deliveries).markSucceeded(deliveryId, 3, 200);
      assertThat(metricCount(WebhookMetrics.SUCCEEDED)).isEqualTo(1);
      assertThat(metricCount(WebhookMetrics.FAILED)).isZero();
    }

    @Test
    @DisplayName("Exhaustion lifecycle: 7 retries followed by 8th failure permanently marks FAILED")
    void exhaustionMarksFailedPermanently() {
      // Prior attempts = 7; this is attempt 8 (the limit)
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 7)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(500));

      deliveryService.attempt(deliveryId);

      verify(deliveries).markFailed(deliveryId, 8, 500, "HTTP 500 (gave up after 8 attempts)");
      verify(deliveries, never()).markRetry(any(), anyInt(), any(), any(), any());
      assertThat(metricCount(WebhookMetrics.FAILED)).isEqualTo(1);
    }
  }

  // =========================================================================
  // 5. Observability & Health Monitoring
  // =========================================================================
  @Nested
  @DisplayName("5. Observability and Health Indicator")
  class ObservabilityAndHealthTests {

    @Test
    @DisplayName("Health transitions to DOWN when recent failures are present in window")
    void healthDownOnRecentFailures() {
      WebhookDeliveryRepository mockRepo = mock(WebhookDeliveryRepository.class);
      WebhookHealthIndicator indicator = new WebhookHealthIndicator(mockRepo, properties, clock);

      when(mockRepo.countFailedSince(
          eq(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).minusMinutes(60)),
          eq(WebhookDeliveryService.DISABLED_ERROR)))
          .thenReturn(3L);
      when(mockRepo.oldestDueAt()).thenReturn(Optional.empty());

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails()).containsEntry("failedRecently", 3L);
      assertThat(health.getDetails().get("reason").toString()).contains("failed permanently");
    }

    @Test
    @DisplayName("Health transitions to DOWN when an open delivery is overdue > 10 minutes")
    void healthDownOnStuckOverdueDelivery() {
      WebhookDeliveryRepository mockRepo = mock(WebhookDeliveryRepository.class);
      WebhookHealthIndicator indicator = new WebhookHealthIndicator(mockRepo, properties, clock);

      when(mockRepo.countFailedSince(any(), any())).thenReturn(0L);
      when(mockRepo.oldestDueAt()).thenReturn(
          Optional.of(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).minusMinutes(15)));

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails().get("reason").toString()).contains("overdue");
      assertThat((Long) health.getDetails().get("oldestOverdueSeconds")).isGreaterThan(600L);
    }

    @Test
    @DisplayName("Health is UP when deliveries are processed normally within threshold")
    void healthUpWhenHealthy() {
      WebhookDeliveryRepository mockRepo = mock(WebhookDeliveryRepository.class);
      WebhookHealthIndicator indicator = new WebhookHealthIndicator(mockRepo, properties, clock);

      when(mockRepo.countOpen()).thenReturn(1L);
      when(mockRepo.countFailedSince(any(), any())).thenReturn(0L);
      when(mockRepo.oldestDueAt()).thenReturn(
          Optional.of(OffsetDateTime.ofInstant(T0, ZoneOffset.UTC).minusMinutes(2)));

      Health health = indicator.health();

      assertThat(health.getStatus()).isEqualTo(Status.UP);
      assertThat(health.getDetails()).containsEntry("pending", 1L);
      assertThat(health.getDetails()).containsEntry("failedRecently", 0L);
      assertThat(health.getDetails()).containsEntry("enabled", true);
    }

    @Test
    @DisplayName("Tagged attempt metrics increment accurately by response_class and outcome without double-counting")
    void taggedAttemptMetrics() {
      // 1. Success 200
      UUID id1 = UUID.randomUUID();
      when(deliveries.lockForAttempt(id1)).thenReturn(Optional.of(createAttemptRow(id1, "https://webhook.acme.com/v1", true, 0)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(200));
      deliveryService.attempt(id1);

      assertThat(meterRegistry.find(WebhookMetrics.ATTEMPTS).tag("response_class", "successful").tag("outcome", "succeeded").counter().count()).isEqualTo(1.0);

      // 2. Retryable failure 503
      UUID id2 = UUID.randomUUID();
      when(deliveries.lockForAttempt(id2)).thenReturn(Optional.of(createAttemptRow(id2, "https://webhook.acme.com/v1", true, 1)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(503));
      deliveryService.attempt(id2);

      assertThat(meterRegistry.find(WebhookMetrics.ATTEMPTS).tag("response_class", "retryable_failure").tag("outcome", "retried").counter().count()).isEqualTo(1.0);

      // 3. Permanent non-retryable 400
      UUID id3 = UUID.randomUUID();
      when(deliveries.lockForAttempt(id3)).thenReturn(Optional.of(createAttemptRow(id3, "https://webhook.acme.com/v1", true, 0)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(400));
      deliveryService.attempt(id3);

      assertThat(meterRegistry.find(WebhookMetrics.ATTEMPTS).tag("response_class", "non_retryable_failure").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.find(WebhookMetrics.TERMINAL).tag("category", "non_retryable_status").counter().count()).isEqualTo(1.0);

      // 4. Exhaustion at attempt 8
      UUID id4 = UUID.randomUUID();
      when(deliveries.lockForAttempt(id4)).thenReturn(Optional.of(createAttemptRow(id4, "https://webhook.acme.com/v1", true, 7)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(500));
      deliveryService.attempt(id4);

      assertThat(meterRegistry.find(WebhookMetrics.ATTEMPTS).tag("response_class", "retryable_failure").tag("outcome", "failed").counter().count()).isEqualTo(1.0);
      assertThat(meterRegistry.find(WebhookMetrics.TERMINAL).tag("category", "exhausted_retries").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Terminal failures for disabled subscription and invalid endpoints increment terminal metrics")
    void terminalFailureMetrics() {
      // Disabled subscription
      UUID idDisabled = UUID.randomUUID();
      when(deliveries.lockForAttempt(idDisabled)).thenReturn(Optional.of(createAttemptRow(idDisabled, "https://webhook.acme.com/v1", false, 0)));
      deliveryService.attempt(idDisabled);

      assertThat(meterRegistry.find(WebhookMetrics.TERMINAL).tag("category", "subscription_disabled").counter().count()).isEqualTo(1.0);

      // Blocked internal URL
      UUID idBlocked = UUID.randomUUID();
      when(deliveries.lockForAttempt(idBlocked)).thenReturn(Optional.of(createAttemptRow(idBlocked, "https://blocked-internal.acme.com/v1", true, 0)));
      deliveryService.attempt(idBlocked);

      assertThat(meterRegistry.find(WebhookMetrics.TERMINAL).tag("category", "endpoint_not_allowed").counter().count()).isEqualTo(1.0);
    }
  }

  // =========================================================================
  // 6. Security & Credential Exclusion from Logs
  // =========================================================================
  @Nested
  @DisplayName("6. Credential and secret exclusion from logs")
  class LogSanitizationTests {

    @Test
    @DisplayName("Logs under timeout, server failure and exhaustion never expose secrets, signatures or payload")
    void logsNeverContainSensitiveData() {
      String expectedSignature = signer.sign(SECRET_KEY, T0.getEpochSecond(), PAYLOAD_BODY);

      // 1. Timeout attempt
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 0)));
      when(sender.send(any())).thenReturn(WebhookSendResult.timeout());
      deliveryService.attempt(deliveryId);

      // 2. 503 attempt
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 1)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(503));
      deliveryService.attempt(deliveryId);

      // 3. Exhaustion attempt (attempt 8)
      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(createAttemptRow("https://webhook.acme.com/v1", true, 7)));
      when(sender.send(any())).thenReturn(WebhookSendResult.response(500));
      deliveryService.attempt(deliveryId);

      // Verify captured log messages
      assertThat(capturedLogs.list).isNotEmpty();
      assertThat(capturedLogs.list).extracting(ILoggingEvent::getFormattedMessage)
          .allSatisfy(msg -> {
            assertThat(msg)
                .as("Log message must not leak merchant secret")
                .doesNotContain(SECRET_KEY);
            assertThat(msg)
                .as("Log message must not leak HMAC signature")
                .doesNotContain(expectedSignature);
            assertThat(msg)
                .as("Log message must not leak customer payload data")
                .doesNotContain("ZT-ZD37TEST88", "OUT_FOR_DELIVERY");
            assertThat(msg)
                .as("Log message should contain eventId for tracing")
                .contains(eventId.toString());
          });

      assertThat(capturedLogs.list).extracting(ILoggingEvent::getFormattedMessage)
          .anySatisfy(msg -> assertThat(msg).contains("responseClass=RETRYABLE_FAILURE"))
          .anySatisfy(msg -> assertThat(msg).contains("terminalCategory=EXHAUSTED_RETRIES"));
    }

    @Test
    @DisplayName("Undecryptable secret fails securely without attempting send")
    void undecryptableSecretFailsSecurely() {
      DeliveryAttempt corruptedSecret = new DeliveryAttempt(
          deliveryId,
          subscriptionId,
          eventId,
          "parcel.status.updated",
          parcelId,
          PAYLOAD_BODY,
          0,
          OffsetDateTime.parse("2026-12-06T09:59:30Z"),
          "different-merchant-unmatched",
          "https://webhook.acme.com/v1",
          cipher.encrypt(SECRET_KEY, MERCHANT_ID), // encrypted with MERCHANT_ID, but row has different-merchant
          true);

      when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(corruptedSecret));

      deliveryService.attempt(deliveryId);

      verifyNoInteractions(sender);
      verify(deliveries).markFailed(deliveryId, 1, null, "Signing secret unavailable");
    }
  }
}
