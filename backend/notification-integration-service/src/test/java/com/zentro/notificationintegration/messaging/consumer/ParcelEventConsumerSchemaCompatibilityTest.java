package com.zentro.notificationintegration.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zentro.notificationintegration.delivery.service.NotificationDispatcher;
import com.zentro.notificationintegration.messaging.event.MalformedEventException;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import com.zentro.notificationintegration.notification.service.NotificationService;
import com.zentro.notificationintegration.webhook.event.MerchantEventReader;
import com.zentro.notificationintegration.webhook.event.MerchantParcelEvent;
import com.zentro.notificationintegration.webhook.service.WebhookDeliveryService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

class ParcelEventConsumerSchemaCompatibilityTest {

  private static final UUID EVENT_ID = UUID.fromString("6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10");
  private static final UUID PARCEL_ID = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
  private static final String TRACKING_REF = "ZT-ABCDEFGH12";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private ParcelEventParser realParser;
  private MerchantEventReader realReader;

  private NotificationService notificationService;
  private NotificationDispatcher notificationDispatcher;
  private ParcelEventConsumer notificationConsumer;

  private WebhookDeliveryService webhookService;
  private MerchantWebhookConsumer webhookConsumer;

  private Acknowledgment ack;

  @BeforeEach
  void setUp() {
    realParser = new ParcelEventParser(objectMapper);
    realReader = new MerchantEventReader(realParser, objectMapper);

    notificationService = mock(NotificationService.class);
    notificationDispatcher = mock(NotificationDispatcher.class);
    notificationConsumer = new ParcelEventConsumer(realParser, notificationService, notificationDispatcher);

    webhookService = mock(WebhookDeliveryService.class);
    webhookConsumer = new MerchantWebhookConsumer(realReader, webhookService);

    ack = mock(Acknowledgment.class);
  }

  private String validCreatedMessage() {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", EVENT_ID.toString())
        .put("eventType", "parcel.created")
        .put("occurredAt", "2026-11-05T10:15:00Z")
        .put("aggregateId", PARCEL_ID.toString());
    envelope.putObject("payload")
        .put("trackingRef", TRACKING_REF)
        .put("status", "CREATED")
        .put("source", "CUSTOMER_BOOKING")
        .put("occurredAt", "2026-11-05T10:15:00Z");
    return envelope.toString();
  }

  private String validStatusMessage(String status, String failureReason, String merchantId) {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", EVENT_ID.toString())
        .put("eventType", "parcel.status.updated")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z")
        .put("aggregateId", PARCEL_ID.toString());
    ObjectNode payload = envelope.putObject("payload")
        .put("trackingRef", TRACKING_REF)
        .put("previousStatus", "PICKED_UP")
        .put("status", status)
        .put("actorRole", "DISPATCHER")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z");
    if (failureReason != null) {
      payload.put("failureReason", failureReason);
    }
    if (merchantId != null) {
      payload.put("merchantId", merchantId);
    }
    return envelope.toString();
  }

  private ConsumerRecord<String, String> record(String topic, String value, String schemaVersion, String headerEventId) {
    RecordHeaders headers = new RecordHeaders();
    if (schemaVersion != null) {
      headers.add("schemaVersion", schemaVersion.getBytes(StandardCharsets.UTF_8));
    }
    if (headerEventId != null) {
      headers.add("eventId", headerEventId.getBytes(StandardCharsets.UTF_8));
    }
    return new ConsumerRecord<>(topic, 0, 100L, 0L, null, 0, 0, PARCEL_ID.toString(), value, headers, null);
  }

  @Test
  @DisplayName("Notification consumer accepts valid event with schemaVersion=1")
  void notificationConsumerValidV1() {
    ConsumerRecord<String, String> rec = record("parcel.created", validCreatedMessage(), "1", EVENT_ID.toString());
    UUID deliveryId = UUID.randomUUID();
    when(notificationService.handle(any())).thenReturn(Optional.of(deliveryId));

    notificationConsumer.onParcelEvent(rec, ack);

    verify(notificationService).handle(any(ParcelEvent.class));
    verify(notificationDispatcher).deliver(deliveryId);
    verify(ack).acknowledge();
    verify(notificationService, never()).reject(any(), any(), any(), any());
  }

  @Test
  @DisplayName("Notification consumer accepts legacy event without schemaVersion header (defaults to v1)")
  void notificationConsumerLegacyNoSchemaVersion() {
    ConsumerRecord<String, String> rec = record("parcel.created", validCreatedMessage(), null, EVENT_ID.toString());
    when(notificationService.handle(any())).thenReturn(Optional.empty());

    notificationConsumer.onParcelEvent(rec, ack);

    verify(notificationService).handle(any(ParcelEvent.class));
    verify(ack).acknowledge();
    verify(notificationService, never()).reject(any(), any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "0", "abc", "99", "v1"})
  @DisplayName("Notification consumer rejects unsupported schemaVersion and commits offset")
  void notificationConsumerUnsupportedSchemaVersion(String version) {
    ConsumerRecord<String, String> rec = record("parcel.created", validCreatedMessage(), version, EVENT_ID.toString());

    notificationConsumer.onParcelEvent(rec, ack);

    verify(notificationService).reject(eq("parcel.created"), eq(0), eq(100L), eq("unsupported schema version"));
    verify(ack).acknowledge();
    verify(notificationService, never()).handle(any());
  }

  @Test
  @DisplayName("Notification consumer rejects conflicting header eventId")
  void notificationConsumerConflictingHeaderEventId() {
    ConsumerRecord<String, String> rec = record("parcel.created", validCreatedMessage(), "1", UUID.randomUUID().toString());

    notificationConsumer.onParcelEvent(rec, ack);

    verify(notificationService).reject(eq("parcel.created"), eq(0), eq(100L), eq("conflicting eventId"));
    verify(ack).acknowledge();
    verify(notificationService, never()).handle(any());
  }

  @Test
  @DisplayName("Webhook consumer accepts valid merchant status event with schemaVersion=1")
  void webhookConsumerValidV1() {
    String msg = validStatusMessage("DELIVERED", null, "merchant-sub-1");
    ConsumerRecord<String, String> rec = record("parcel.status.updated", msg, "1", EVENT_ID.toString());
    UUID webhookId = UUID.randomUUID();
    when(webhookService.handle(any())).thenReturn(Optional.of(webhookId));

    webhookConsumer.onParcelEvent(rec, ack);

    ArgumentCaptor<MerchantParcelEvent> captor = ArgumentCaptor.forClass(MerchantParcelEvent.class);
    verify(webhookService).handle(captor.capture());
    assertThat(captor.getValue().merchantId()).isEqualTo("merchant-sub-1");
    assertThat(captor.getValue().event().status()).isEqualTo("DELIVERED");
    verify(webhookService).attempt(webhookId);
    verify(ack).acknowledge();
    verify(webhookService, never()).reject(any(), any(), any(), any());
  }

  @Test
  @DisplayName("Webhook consumer accepts legacy event without schemaVersion header (defaults to v1)")
  void webhookConsumerLegacyNoSchemaVersion() {
    String msg = validStatusMessage("DELIVERED", null, "merchant-sub-1");
    ConsumerRecord<String, String> rec = record("parcel.status.updated", msg, null, EVENT_ID.toString());
    when(webhookService.handle(any())).thenReturn(Optional.empty());

    webhookConsumer.onParcelEvent(rec, ack);

    verify(webhookService).handle(any());
    verify(ack).acknowledge();
    verify(webhookService, never()).reject(any(), any(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "0", "abc", "99", "v1"})
  @DisplayName("Webhook consumer rejects unsupported schemaVersion and commits offset")
  void webhookConsumerUnsupportedSchemaVersion(String version) {
    String msg = validStatusMessage("DELIVERED", null, "merchant-sub-1");
    ConsumerRecord<String, String> rec = record("parcel.status.updated", msg, version, EVENT_ID.toString());

    webhookConsumer.onParcelEvent(rec, ack);

    verify(webhookService).reject(eq("parcel.status.updated"), eq(0), eq(100L), eq("unsupported schema version"));
    verify(ack).acknowledge();
    verify(webhookService, never()).handle(any());
  }

  @Test
  @DisplayName("Webhook consumer rejects conflicting header eventId")
  void webhookConsumerConflictingHeaderEventId() {
    String msg = validStatusMessage("DELIVERED", null, "merchant-sub-1");
    ConsumerRecord<String, String> rec = record("parcel.status.updated", msg, "1", UUID.randomUUID().toString());

    webhookConsumer.onParcelEvent(rec, ack);

    verify(webhookService).reject(eq("parcel.status.updated"), eq(0), eq(100L), eq("conflicting eventId"));
    verify(ack).acknowledge();
    verify(webhookService, never()).handle(any());
  }
}
