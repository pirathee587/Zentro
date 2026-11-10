package com.zentro.trackingreporting.messaging.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrackingKafkaConsumerTest {

  @Mock private TrackingService trackingService;
  @Mock private ProcessedEventRepository processedEventRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();
  private TrackingKafkaConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new TrackingKafkaConsumer(trackingService, processedEventRepository, objectMapper);
  }

  @Test
  @DisplayName("Processes parcel.status.updated event and records milestone")
  void consumesParcelStatusUpdated() {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String message = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "occurredAt": "2026-11-04T12:00:00Z",
          "aggregateId": "%s",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "PICKED_UP",
            "status": "IN_TRANSIT",
            "actorRole": "DISPATCHER",
            "occurredAt": "2026-11-04T12:00:00Z"
          }
        }
        """.formatted(eventId, parcelId);

    when(processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId))
        .thenReturn(false);

    consumer.onParcelEvent(message, "parcel.status.updated", eventId.toString());

    verify(trackingService).recordEvent(
        eq("ZT-7K3QX9M2PA"),
        eq(parcelId),
        eq(TrackingStatus.IN_TRANSIT),
        eq("PICKED_UP"),
        eq("DISPATCHER"),
        eq("In Transit"),
        eq("Processed through sorting facility"),
        eq(null),
        eq(null),
        any(),
        eq(eventId)
    );

    verify(processedEventRepository).save(any(ProcessedEvent.class));
  }

  @Test
  @DisplayName("Skips duplicate events when eventId already processed")
  void skipsDuplicateEvent() {
    UUID eventId = UUID.randomUUID();
    String message = """
        {
          "eventId": "%s",
          "eventType": "parcel.created",
          "occurredAt": "2026-11-04T10:00:00Z",
          "aggregateId": "%s",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "status": "CREATED",
            "source": "CUSTOMER_BOOKING",
            "occurredAt": "2026-11-04T10:00:00Z"
          }
        }
        """.formatted(eventId, UUID.randomUUID());

    when(processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId))
        .thenReturn(true);

    consumer.onParcelEvent(message, "parcel.created", eventId.toString());

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards envelope without payload and does not record or mark it processed")
  void discardsEnvelopeWithoutPayload() {
    UUID eventId = UUID.randomUUID();
    String message = """
        {"eventId": "%s", "eventType": "parcel.created", "aggregateId": "%s"}
        """.formatted(eventId, UUID.randomUUID());
    when(processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId))
        .thenReturn(false);

    consumer.onParcelEvent(message, "parcel.created", null);

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards event without trackingRef and does not record or mark it processed")
  void discardsEventWithoutTrackingRef() {
    UUID eventId = UUID.randomUUID();
    String message = """
        {"eventId": "%s", "eventType": "parcel.status.updated", "payload": {"status": "IN_TRANSIT"}}
        """.formatted(eventId);
    when(processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId))
        .thenReturn(false);

    consumer.onParcelEvent(message, "parcel.status.updated", null);

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Processing failure is rethrown so Kafka redelivers, and the event is not marked processed")
  void processingFailureIsRethrownAndNotMarkedProcessed() {
    UUID eventId = UUID.randomUUID();
    String message = """
        {"eventId": "%s", "eventType": "parcel.status.updated", "aggregateId": "%s",
         "occurredAt": "2026-11-04T12:00:00Z",
         "payload": {"trackingRef": "ZT-7K3QX9M2PA", "previousStatus": "OUT_FOR_DELIVERY", "status": "DELIVERED", "actorRole": "DISPATCHER"}}
        """.formatted(eventId, UUID.randomUUID());
    when(processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId))
        .thenReturn(false);
    when(trackingService.recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("database unavailable"));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> consumer.onParcelEvent(message, "parcel.status.updated", null))
        .isInstanceOf(RuntimeException.class)
        .hasRootCauseInstanceOf(IllegalStateException.class);
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Malformed JSON is rethrown as a processing error and nothing is recorded")
  void malformedJsonIsRethrown() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> consumer.onParcelEvent("{not-json", "parcel.created", null))
        .isInstanceOf(RuntimeException.class);
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards envelope when payload is not a JSON object")
  void discardsEnvelopeWhenNotAnObject() {
    consumer.onParcelEvent("\"plain-string\"", "parcel.created", null);
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards event when eventId string is not a valid UUID")
  void discardsEventWithInvalidEventId() {
    String message = """
        {"eventId": "not-a-valid-uuid", "eventType": "parcel.created", "payload": {"trackingRef": "ZT-12345"}}
        """;
    consumer.onParcelEvent(message, "parcel.created", null);
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards event when aggregateId or occurredAt is malformed")
  void discardsMalformedAggregateIdAndOccurredAt() {
    UUID eventId = UUID.randomUUID();
    String messageBadAggregate = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "invalid-uuid",
          "occurredAt": "2026-11-04T12:00:00Z",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "DELIVERED",
            "actorRole": "DISPATCHER"
          }
        }
        """.formatted(eventId);

    consumer.onParcelEvent(messageBadAggregate, "parcel.status.updated", null);
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());

    String messageBadDate = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "%s",
          "occurredAt": "invalid-date",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "DELIVERED",
            "actorRole": "DISPATCHER"
          }
        }
        """.formatted(eventId, UUID.randomUUID());

    consumer.onParcelEvent(messageBadDate, "parcel.status.updated", null);
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards event when envelope eventId conflicts with header eventId")
  void discardsConflictingEventId() {
    UUID envelopeId = UUID.randomUUID();
    UUID headerId = UUID.randomUUID();
    String message = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "%s",
          "occurredAt": "2026-11-04T12:00:00Z",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "DELIVERED",
            "actorRole": "DISPATCHER"
          }
        }
        """.formatted(envelopeId, UUID.randomUUID());

    consumer.onParcelEvent(message, "parcel.status.updated", headerId.toString());

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards event when schemaVersion is unsupported")
  void discardsUnsupportedSchemaVersion() {
    UUID eventId = UUID.randomUUID();
    String message = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "%s",
          "occurredAt": "2026-11-04T12:00:00Z",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "DELIVERED",
            "actorRole": "DISPATCHER"
          }
        }
        """.formatted(eventId, UUID.randomUUID());

    consumer.onParcelEvent(message, "parcel.status.updated", eventId.toString(), "2");

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards event when trackingRef format does not match Crockford base32 regex")
  void discardsInvalidTrackingRefFormat() {
    UUID eventId = UUID.randomUUID();
    String message = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "%s",
          "occurredAt": "2026-11-04T12:00:00Z",
          "payload": {
            "trackingRef": "INVALID-REF-123",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "DELIVERED",
            "actorRole": "DISPATCHER"
          }
        }
        """.formatted(eventId, UUID.randomUUID());

    consumer.onParcelEvent(message, "parcel.status.updated", eventId.toString(), "1");

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards event when status is unrecognized")
  void discardsInvalidStatus() {
    UUID eventId = UUID.randomUUID();
    String message = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "%s",
          "occurredAt": "2026-11-04T12:00:00Z",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "FLYING",
            "actorRole": "DISPATCHER"
          }
        }
        """.formatted(eventId, UUID.randomUUID());

    consumer.onParcelEvent(message, "parcel.status.updated", eventId.toString(), "1");

    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }

  @Test
  @DisplayName("Discards FAILED event missing failureReason, and DELIVERED event with unexpected failureReason")
  void failureReasonValidation() {
    UUID eventId1 = UUID.randomUUID();
    String messageFailedMissing = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "%s",
          "occurredAt": "2026-11-04T12:00:00Z",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "FAILED",
            "actorRole": "DISPATCHER"
          }
        }
        """.formatted(eventId1, UUID.randomUUID());

    consumer.onParcelEvent(messageFailedMissing, "parcel.status.updated", null);
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

    UUID eventId2 = UUID.randomUUID();
    String messageDeliveredWithReason = """
        {
          "eventId": "%s",
          "eventType": "parcel.status.updated",
          "aggregateId": "%s",
          "occurredAt": "2026-11-04T12:00:00Z",
          "payload": {
            "trackingRef": "ZT-7K3QX9M2PA",
            "previousStatus": "OUT_FOR_DELIVERY",
            "status": "DELIVERED",
            "actorRole": "DISPATCHER",
            "failureReason": "should not be here"
          }
        }
        """.formatted(eventId2, UUID.randomUUID());

    consumer.onParcelEvent(messageDeliveredWithReason, "parcel.status.updated", null);
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(processedEventRepository, never()).save(any());
  }
}

