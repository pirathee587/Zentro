package com.zentro.trackingreporting.messaging.consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.reporting.DeliveryProjectionRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrackingKafkaConsumerTest {
  @Mock TrackingService tracking;
  @Mock ProcessedEventRepository processed;
  @Mock DeliveryProjectionRepository projections;
  TrackingKafkaConsumer consumer;
  final UUID id = UUID.randomUUID();

  @BeforeEach void setup() { consumer = new TrackingKafkaConsumer(tracking, processed, new ObjectMapper(), projections); }

  String valid() {
    return """
        {"eventId":"%s","eventType":"parcel.created","aggregateId":"%s","occurredAt":"2026-11-05T08:00:00Z",
         "payload":{"trackingRef":"ZT-7K3QX9M2PA","status":"CREATED","source":"CUSTOMER_BOOKING","occurredAt":"2026-11-05T08:00:00Z"}}
        """.formatted(id, UUID.randomUUID());
  }

  @Test void validEventClaimsBeforeApplying() {
    when(processed.claim(anyString(), eq(id))).thenReturn(1);
    consumer.onParcelEvent(valid(), "parcel.created", null);
    var order = inOrder(processed, projections, tracking);
    order.verify(processed).claim("tracking-reporting-service", id);
    order.verify(projections).lock(any());
    order.verify(tracking).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(id));
    order.verify(processed).flush();
    order.verify(projections).refresh(any());
  }

  @Test void duplicateClaimHasNoEffect() {
    consumer.onParcelEvent(valid(), "parcel.created", null);
    verifyNoInteractions(tracking, projections);
  }

  @Test void unsupportedHeaderVersionHasNoEffect() {
    consumer.onParcelEvent(valid(), "parcel.created", null, "999");
    verifyNoInteractions(processed, tracking, projections);
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

  @ParameterizedTest @ValueSource(strings = {"{not-json", "null", "[]", "{}", "\"text\""})
  void invalidEnvelopeIsSkippedWithoutDatabaseAccess(String value) {
    consumer.onParcelEvent(value, "parcel.created", id.toString());
    verifyNoInteractions(processed, tracking, projections);
  }

  @ParameterizedTest @ValueSource(strings = {"eventId", "aggregateId", "occurredAt", "trackingRef", "source", "status"})
  void missingRequiredValuesNeverInventFacts(String field) throws Exception {
    var mapper = new ObjectMapper();
    var e = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid());
    e.remove(field);
    ((com.fasterxml.jackson.databind.node.ObjectNode)e.path("payload")).remove(field);
    consumer.onParcelEvent(e.toString(), "parcel.created", id.toString());
    verifyNoInteractions(processed, tracking, projections);
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
