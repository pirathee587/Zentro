package com.zentro.trackingreporting.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEvent;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class TrackingKafkaConsumerSchemaCompatibilityTest {

  private static final UUID EVENT_ID = UUID.fromString("6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10");
  private static final UUID PARCEL_ID = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
  private static final String TRACKING_REF = "ZT-ABCDEFGH12";

  @Mock private TrackingService trackingService;
  @Mock private ProcessedEventRepository processedEventRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private TrackingKafkaConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new TrackingKafkaConsumer(trackingService, processedEventRepository, objectMapper);
  }

  private ObjectNode validCreatedEnvelope() {
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
    return envelope;
  }

  private ObjectNode validStatusEnvelope(String status, String failureReason) {
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
    return envelope;
  }

  @Test
  @DisplayName("Valid parcel.created event with schemaVersion=1 is processed")
  void validParcelCreatedEventProcessed() {
    String message = validCreatedEnvelope().toString();
    consumer.onParcelEvent(message, "parcel.created", EVENT_ID.toString(), "1");

    verify(trackingService).recordEvent(
        eq(TRACKING_REF), eq(PARCEL_ID), eq(TrackingStatus.CREATED),
        eq(null), eq("SYSTEM"), any(), any(), eq(null), eq(null), any(), eq(EVENT_ID));
    verify(processedEventRepository).save(any(ProcessedEvent.class));
  }

  @Test
  @DisplayName("Legacy parcel.created event without schemaVersion header defaults to v1 and is processed")
  void legacyCreatedEventWithoutSchemaVersionProcessed() {
    String message = validCreatedEnvelope().toString();
    consumer.onParcelEvent(message, "parcel.created", EVENT_ID.toString(), null);

    verify(trackingService).recordEvent(
        eq(TRACKING_REF), eq(PARCEL_ID), eq(TrackingStatus.CREATED),
        eq(null), eq("SYSTEM"), any(), any(), eq(null), eq(null), any(), eq(EVENT_ID));
    verify(processedEventRepository).save(any(ProcessedEvent.class));
  }

  @Test
  @DisplayName("Valid parcel.status.updated event with byte[] schemaVersion=1 is processed")
  void validParcelStatusUpdatedWithByteHeaderProcessed() {
    String message = validStatusEnvelope("IN_TRANSIT", null).toString();
    consumer.onParcelEvent(message, "parcel.status.updated", EVENT_ID.toString(), "1".getBytes(StandardCharsets.UTF_8));

    verify(trackingService).recordEvent(
        eq(TRACKING_REF), eq(PARCEL_ID), eq(TrackingStatus.IN_TRANSIT),
        eq("PICKED_UP"), eq("DISPATCHER"), any(), any(), eq(null), eq(null), any(), eq(EVENT_ID));
    verify(processedEventRepository).save(any(ProcessedEvent.class));
  }

  @Test
  @DisplayName("Valid status event with optional merchantId is forward compatible")
  void statusEventWithMerchantIdProcessed() {
    ObjectNode envelope = validStatusEnvelope("DELIVERED", null);
    ((ObjectNode) envelope.get("payload")).put("merchantId", "merchant-sub-42");

    consumer.onParcelEvent(envelope.toString(), "parcel.status.updated", EVENT_ID.toString(), "1");

    verify(trackingService).recordEvent(
        eq(TRACKING_REF), eq(PARCEL_ID), eq(TrackingStatus.DELIVERED),
        eq("PICKED_UP"), eq("DISPATCHER"), any(), any(), eq(null), eq(null), any(), eq(EVENT_ID));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2", "0", "abc", "v1", "99"})
  @DisplayName("Unsupported schemaVersion is discarded with category=unsupported_schema_version")
  void unsupportedSchemaVersionRejected(String version, CapturedOutput output) {
    consumer.onParcelEvent(validCreatedEnvelope().toString(), "parcel.created", EVENT_ID.toString(), version);

    assertThat(output.getAll()).contains("category=unsupported_schema_version");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Missing eventId is discarded with category=missing_event_id")
  void missingEventIdRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    envelope.remove("eventId");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", null, "1");

    assertThat(output.getAll()).contains("category=missing_event_id");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Invalid UUID eventId is discarded with category=invalid_event_id")
  void invalidEventIdRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    envelope.put("eventId", "not-a-uuid");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", null, "1");

    assertThat(output.getAll()).contains("category=invalid_event_id");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Conflicting eventId between envelope and header is discarded with category=conflicting_event_id")
  void conflictingEventIdRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    String headerId = UUID.randomUUID().toString();

    consumer.onParcelEvent(envelope.toString(), "parcel.created", headerId, "1");

    assertThat(output.getAll()).contains("category=conflicting_event_id");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Missing occurredAt timestamp is discarded with category=missing_occurred_at")
  void missingOccurredAtRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    envelope.remove("occurredAt");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=missing_occurred_at");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Malformed occurredAt timestamp is discarded with category=invalid_occurred_at")
  void malformedOccurredAtRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    envelope.put("occurredAt", "not-a-valid-timestamp");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=invalid_occurred_at");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Missing aggregateId is discarded with category=missing_aggregate_id")
  void missingAggregateIdRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    envelope.remove("aggregateId");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=missing_aggregate_id");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Invalid aggregateId is discarded with category=invalid_aggregate_id")
  void invalidAggregateIdRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    envelope.put("aggregateId", "12345");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=invalid_aggregate_id");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Invalid trackingRef regex is discarded with category=invalid_tracking_ref")
  void invalidTrackingRefRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    ((ObjectNode) envelope.get("payload")).put("trackingRef", "INVALID_TRACKING");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=invalid_tracking_ref");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Mismatched eventType and topic is discarded with category=mismatched_event_type")
  void mismatchedEventTypeRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();

    consumer.onParcelEvent(envelope.toString(), "parcel.status.updated", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=mismatched_event_type");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("parcel.created with non-CREATED status is discarded with category=invalid_status")
  void createdMustBeCreated(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    ((ObjectNode) envelope.get("payload")).put("status", "PICKED_UP");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=invalid_status");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Missing source in parcel.created is discarded with category=missing_source")
  void missingSourceRejected(CapturedOutput output) {
    ObjectNode envelope = validCreatedEnvelope();
    ((ObjectNode) envelope.get("payload")).remove("source");

    consumer.onParcelEvent(envelope.toString(), "parcel.created", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=missing_source");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Invalid actorRole in parcel.status.updated is discarded with category=invalid_actor_role")
  void invalidActorRoleRejected(CapturedOutput output) {
    ObjectNode envelope = validStatusEnvelope("IN_TRANSIT", null);
    ((ObjectNode) envelope.get("payload")).put("actorRole", "invalid role 123");

    consumer.onParcelEvent(envelope.toString(), "parcel.status.updated", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=invalid_actor_role");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("FAILED status with valid failureReason is processed")
  void failedStatusWithReasonProcessed() {
    ObjectNode envelope = validStatusEnvelope("FAILED", "Recipient not available");

    consumer.onParcelEvent(envelope.toString(), "parcel.status.updated", EVENT_ID.toString(), "1");

    verify(trackingService).recordEvent(
        eq(TRACKING_REF), eq(PARCEL_ID), eq(TrackingStatus.FAILED),
        eq("PICKED_UP"), eq("DISPATCHER"), any(), eq("Recipient not available"),
        eq("Recipient not available"), eq(null), any(), eq(EVENT_ID));
  }

  @Test
  @DisplayName("FAILED status missing failureReason is discarded with category=missing_failure_reason")
  void failedStatusMissingReasonRejected(CapturedOutput output) {
    ObjectNode envelope = validStatusEnvelope("FAILED", null);

    consumer.onParcelEvent(envelope.toString(), "parcel.status.updated", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=missing_failure_reason");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("DELIVERED status with unexpected failureReason is discarded with category=unexpected_failure_reason")
  void nonFailedStatusWithReasonRejected(CapturedOutput output) {
    ObjectNode envelope = validStatusEnvelope("DELIVERED", "Should not be here");

    consumer.onParcelEvent(envelope.toString(), "parcel.status.updated", EVENT_ID.toString(), "1");

    assertThat(output.getAll()).contains("category=unexpected_failure_reason");
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("Additive unmapped properties do not break consumer (forward compatibility)")
  void forwardCompatibleAdditiveProperties() {
    ObjectNode envelope = validStatusEnvelope("IN_TRANSIT", null);
    envelope.put("unmappedEnvelopeField", "extra-data");
    ((ObjectNode) envelope.get("payload")).put("unmappedPayloadField", "extra-data");

    consumer.onParcelEvent(envelope.toString(), "parcel.status.updated", EVENT_ID.toString(), "1");

    verify(trackingService).recordEvent(
        eq(TRACKING_REF), eq(PARCEL_ID), eq(TrackingStatus.IN_TRANSIT),
        eq("PICKED_UP"), eq("DISPATCHER"), any(), any(), eq(null), eq(null), any(), eq(EVENT_ID));
  }

  @Test
  @DisplayName("Already processed event is skipped for idempotency")
  void duplicateEventSkipped() {
    when(processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", EVENT_ID))
        .thenReturn(true);

    consumer.onParcelEvent(validCreatedEnvelope().toString(), "parcel.created", EVENT_ID.toString(), "1");

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }
}
