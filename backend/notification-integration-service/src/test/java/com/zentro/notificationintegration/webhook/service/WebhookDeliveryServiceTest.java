package com.zentro.notificationintegration.webhook.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.zentro.notificationintegration.webhook.config.WebhookProperties;
import com.zentro.notificationintegration.webhook.entity.WebhookSubscription;
import com.zentro.notificationintegration.webhook.event.MerchantParcelEvent;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository.DeliveryAttempt;
import com.zentro.notificationintegration.webhook.repository.WebhookSubscriptionRepository;
import com.zentro.notificationintegration.webhook.security.WebhookSecretCipher;
import com.zentro.notificationintegration.webhook.security.WebhookSigner;
import com.zentro.notificationintegration.webhook.validation.WebhookUrlValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class WebhookDeliveryServiceTest {
  @Test void webhookAttemptsRestoreStoredContextAndUseFreshSpans() {
    try (var tracing = new com.zentro.notificationintegration.support.PropagationTestSupport()) {
      service.setPropagation(tracing.context);
      var row = attemptRow("https://hooks.example.com/events", true, 0);
      lock(new DeliveryAttempt(row.id(), row.subscriptionId(), row.eventId(), row.eventType(), row.parcelId(), row.payload(),
          row.attempts(), row.createdAt(), row.merchantId(), row.url(), row.secretCiphertext(), row.subscriptionActive(), tracing.metadata("webhook-143")));
      var spans = new java.util.ArrayList<String>();
      when(sender.send(any())).thenAnswer(invocation -> {
        assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo("webhook-143");
        assertThat(tracing.tracer.currentSpan().context().traceId()).isEqualTo(com.zentro.notificationintegration.support.PropagationTestSupport.TRACE);
        spans.add(tracing.tracer.currentSpan().context().spanId());
        return WebhookSendResult.response(500);
      });
      service.attempt(deliveryId);
      service.attempt(deliveryId);
      assertThat(spans).hasSize(2).doesNotHaveDuplicates();
      assertThat(org.slf4j.MDC.get("correlationId")).isNull();
      assertThat(tracing.tracer.currentSpan()).isNull();
    }
  }
  private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");
  private static final String MERCHANT = "merchant-unit";
  private static final String SECRET = "whsec_unit-test-secret-value";
  private static final String PAYLOAD = "{\"eventId\":\"x\",\"payload\":{\"trackingRef\":\"ZT-ABCDEFGH12\"}}";

  private final NotificationRepository processed = mock(NotificationRepository.class);
  private final WebhookSubscriptionRepository subscriptions = mock(WebhookSubscriptionRepository.class);
  private final WebhookDeliveryRepository deliveries = mock(WebhookDeliveryRepository.class);
  private final WebhookHttpSender sender = mock(WebhookHttpSender.class);
  private final WebhookProperties properties = new WebhookProperties(true, false, KEY, "merchant-webhooks",
      new WebhookProperties.Retry(8, 5000, 10000, 1800000), new WebhookProperties.Http(2000, 5000),
      new WebhookProperties.Health(60, 10));
  private final WebhookSecretCipher cipher = new WebhookSecretCipher(properties);
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final WebhookUrlValidator validator = new WebhookUrlValidator(false, WebhookDeliveryServiceTest::resolve);
  private final WebhookDeliveryService service = new WebhookDeliveryService(processed, subscriptions, deliveries,
      validator, cipher, new WebhookSigner(), sender, new WebhookRetryPolicy(properties),
      new WebhookMetrics(registry, deliveries), properties, Clock.fixed(NOW, ZoneOffset.UTC));

  private final UUID deliveryId = UUID.randomUUID();
  private final UUID eventId = UUID.randomUUID();
  private final UUID parcelId = UUID.randomUUID();
  private final UUID subscriptionId = UUID.randomUUID();
  private ListAppender<ILoggingEvent> logs;

  private static InetAddress[] resolve(String host) throws UnknownHostException {
    return switch (host) {
      case "hooks.example.com" -> new InetAddress[] {InetAddress.getByName("93.184.216.34")};
      case "internal.example.com" -> new InetAddress[] {InetAddress.getByName("10.0.0.7")};
      default -> throw new UnknownHostException(host);
    };
  }

  @BeforeEach
  void captureLogs() {
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(WebhookDeliveryService.class)).addAppender(logs);
  }

  @AfterEach
  void stopLogs() {
    ((Logger) LoggerFactory.getLogger(WebhookDeliveryService.class)).detachAppender(logs);
  }

  private MerchantParcelEvent event(String merchantId) {
    return new MerchantParcelEvent(new ParcelEvent(eventId, "parcel.status.updated", parcelId,
        OffsetDateTime.parse("2026-11-06T07:59:00Z"), "ZT-ABCDEFGH12", "PICKED_UP", null), merchantId, PAYLOAD);
  }

  private DeliveryAttempt attemptRow(String url, boolean active, int attempts) {
    return new DeliveryAttempt(deliveryId, subscriptionId, eventId, "parcel.status.updated", parcelId, PAYLOAD,
        attempts, OffsetDateTime.parse("2026-11-06T07:59:30Z"), MERCHANT, url, cipher.encrypt(SECRET, MERCHANT),
        active);
  }

  private void lock(DeliveryAttempt row) {
    when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.of(row));
  }

  private double count(String name) {
    return registry.counter(name).count();
  }

  @Test
  @DisplayName("A duplicate event is ignored and counted")
  void duplicate() {
    when(processed.markProcessed("merchant-webhooks", eventId)).thenReturn(false);

    assertThat(service.handle(event(MERCHANT))).isEmpty();
    verifyNoInteractions(subscriptions);
    assertThat(count(WebhookMetrics.DUPLICATES)).isEqualTo(1);
  }

  @Test
  @DisplayName("An event without merchantId (customer booking) is marked processed and skipped")
  void customerParcelIsSkipped() {
    when(processed.markProcessed("merchant-webhooks", eventId)).thenReturn(true);

    assertThat(service.handle(event(null))).isEmpty();
    verifyNoInteractions(subscriptions);
    assertThat(count(WebhookMetrics.SKIPPED)).isEqualTo(1);
  }

  @Test
  @DisplayName("An event for a merchant without an active endpoint is skipped")
  void noSubscriptionIsSkipped() {
    when(processed.markProcessed("merchant-webhooks", eventId)).thenReturn(true);
    when(subscriptions.findActiveByMerchant(MERCHANT)).thenReturn(Optional.empty());

    assertThat(service.handle(event(MERCHANT))).isEmpty();
    verify(deliveries, never()).insertPending(any(), any(), any(), any(), any(), any(), any());
    assertThat(count(WebhookMetrics.SKIPPED)).isEqualTo(1);
  }

  @Test
  @DisplayName("A merchant event stores a PENDING delivery with the raw envelope and its SHA-256")
  void storesPendingDelivery() {
    when(processed.markProcessed("merchant-webhooks", eventId)).thenReturn(true);
    when(subscriptions.findActiveByMerchant(MERCHANT)).thenReturn(Optional.of(new WebhookSubscription(
        subscriptionId, MERCHANT, "https://hooks.example.com/h", "v1.x", "whsec_ab", true, null, null)));
    when(deliveries.insertPending(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.of(deliveryId));

    assertThat(service.handle(event(MERCHANT))).contains(deliveryId);
    verify(deliveries).insertPending(subscriptionId, eventId, "parcel.status.updated", parcelId, PAYLOAD,
        WebhookDeliveryService.sha256(PAYLOAD), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    assertThat(WebhookDeliveryService.sha256(PAYLOAD)).hasSize(64);
  }

  @Test
  @DisplayName("2xx: the exact stored body is sent with a valid signature and the delivery SUCCEEDED")
  void successSendsSignedBody() {
    lock(attemptRow("https://hooks.example.com/h", true, 0));
    when(sender.send(any())).thenReturn(WebhookSendResult.response(204));

    service.attempt(deliveryId);

    ArgumentCaptor<SignedRequest> request = ArgumentCaptor.forClass(SignedRequest.class);
    verify(sender).send(request.capture());
    SignedRequest sent = request.getValue();
    assertThat(sent.body()).isEqualTo(PAYLOAD);
    assertThat(sent.timestamp()).isEqualTo(NOW.getEpochSecond());
    assertThat(sent.eventId()).isEqualTo(eventId.toString());
    assertThat(sent.eventType()).isEqualTo("parcel.status.updated");
    assertThat(sent.signature()).isEqualTo(new WebhookSigner().sign(SECRET, NOW.getEpochSecond(), PAYLOAD));
    verify(deliveries).markSucceeded(deliveryId, 1, 204);
    assertThat(count(WebhookMetrics.SUCCEEDED)).isEqualTo(1);
  }

  @Test
  @DisplayName("5xx: RETRY with the response code and the next attempt after the back-off")
  void serverErrorIsRetried() {
    lock(attemptRow("https://hooks.example.com/h", true, 2));
    when(sender.send(any())).thenReturn(WebhookSendResult.response(503));

    service.attempt(deliveryId);

    verify(deliveries).markRetry(deliveryId, 3, 503, "HTTP 503",
        OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(40));
    assertThat(count(WebhookMetrics.RETRIED)).isEqualTo(1);
  }

  @Test
  @DisplayName("Other 4xx: FAILED at once with the response code")
  void clientErrorFails() {
    lock(attemptRow("https://hooks.example.com/h", true, 0));
    when(sender.send(any())).thenReturn(WebhookSendResult.response(400));

    service.attempt(deliveryId);

    verify(deliveries).markFailed(deliveryId, 1, 400, "HTTP 400");
    assertThat(count(WebhookMetrics.FAILED)).isEqualTo(1);
  }

  @Test
  @DisplayName("A disabled endpoint closes the delivery without sending")
  void disabledSubscription() {
    lock(attemptRow("https://hooks.example.com/h", false, 1));

    service.attempt(deliveryId);

    verifyNoInteractions(sender);
    verify(deliveries).markFailed(deliveryId, 1, null, WebhookDeliveryService.DISABLED_ERROR);
    assertThat(count(WebhookMetrics.FAILED)).isZero();
  }

  @Test
  @DisplayName("A later event for the same parcel waits while an earlier one is still open")
  void heldBackForOrder() {
    DeliveryAttempt row = attemptRow("https://hooks.example.com/h", true, 0);
    lock(row);
    when(deliveries.hasEarlierOpen(subscriptionId, parcelId, row.createdAt(), deliveryId)).thenReturn(true);

    service.attempt(deliveryId);

    verifyNoInteractions(sender);
    verify(deliveries).postpone(deliveryId, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusSeconds(10));
    verify(deliveries, never()).markRetry(any(), anyInt(), any(), any(), any());
  }

  @Test
  @DisplayName("An endpoint that now resolves to a private address fails permanently without sending")
  void blockedAtSendTime() {
    lock(attemptRow("https://internal.example.com/h", true, 0));

    service.attempt(deliveryId);

    verifyNoInteractions(sender);
    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(deliveries).markFailed(eq(deliveryId), eq(1), eq(null), error.capture());
    assertThat(error.getValue()).startsWith("Endpoint not allowed: ");
  }

  @Test
  @DisplayName("A host that does not resolve right now is retried")
  void unresolvableIsRetried() {
    lock(attemptRow("https://gone.example.com/h", true, 0));

    service.attempt(deliveryId);

    verifyNoInteractions(sender);
    verify(deliveries).markRetry(eq(deliveryId), eq(1), eq(null), eq("host cannot be resolved"), any());
  }

  @Test
  @DisplayName("A secret that cannot be decrypted fails the delivery without sending")
  void undecryptableSecret() {
    DeliveryAttempt good = attemptRow("https://hooks.example.com/h", true, 0);
    lock(new DeliveryAttempt(good.id(), good.subscriptionId(), good.eventId(), good.eventType(), good.parcelId(),
        good.payload(), 0, good.createdAt(), "another-merchant", good.url(), good.secretCiphertext(), true));

    service.attempt(deliveryId);

    verifyNoInteractions(sender);
    verify(deliveries).markFailed(deliveryId, 1, null, "Signing secret unavailable");
  }

  @Test
  @DisplayName("A delivery that is final or locked elsewhere is left alone")
  void notLockable() {
    when(deliveries.lockForAttempt(deliveryId)).thenReturn(Optional.empty());

    service.attempt(deliveryId);

    verifyNoInteractions(sender);
  }

  @Test
  @DisplayName("Logs carry ids and status codes only: never the secret, the signature or the payload")
  void logsNoSecrets() {
    lock(attemptRow("https://hooks.example.com/h", true, 0));
    when(sender.send(any())).thenReturn(WebhookSendResult.response(500));
    service.attempt(deliveryId);
    lock(attemptRow("https://hooks.example.com/h", true, 7));
    service.attempt(deliveryId);

    String signature = new WebhookSigner().sign(SECRET, NOW.getEpochSecond(), PAYLOAD);
    assertThat(logs.list).isNotEmpty();
    assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
        .allSatisfy(message -> assertThat(message)
            .doesNotContain(SECRET, signature, "ZT-ABCDEFGH12", "trackingRef")
            .contains(eventId.toString()));
    verify(sender, org.mockito.Mockito.times(2)).send(any());
    verify(deliveries).markFailed(eq(deliveryId), eq(8), eq(500), anyString());
  }
}
