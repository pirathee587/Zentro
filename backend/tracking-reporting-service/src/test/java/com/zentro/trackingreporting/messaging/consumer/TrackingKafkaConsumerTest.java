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

  @Test void databaseFailurePropagatesForRollbackAndRetry() {
    when(processed.claim(anyString(), eq(id))).thenReturn(1);
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("synthetic outage")).when(projections).lock(any());
    assertThatThrownBy(() -> consumer.onParcelEvent(valid(), "parcel.created", null))
        .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
  }

  @Test void unknownVersionTypeStatusWrongTypesAndOversizeAreSkipped() throws Exception {
    String v = valid();
    for (String value : new String[]{v + " {}", v.replace("\"eventId\":", "\"eventId\":\"duplicate-value\",\"eventId\":"),
        v.replace("\"eventId\":", "\"schemaVersion\":2,\"eventId\":"),
        v.replace("parcel.created", "delivery.assigned"), v.replace("CREATED", "unknown"),
        v.replace("\"CUSTOMER_BOOKING\"", "123"), v.replace("2026-11-05T08:00:00Z", "invalid-time"), "x".repeat(65537)}) {
      consumer.onParcelEvent(value, "parcel.created", null);
    }
    verifyNoInteractions(processed, tracking, projections);
  }

  @Test
  void assignmentEventUpdatesCourierAssignment() {
    UUID parcelId = UUID.randomUUID();
    UUID courierId = UUID.randomUUID();
    when(processed.claim(anyString(), eq(id))).thenReturn(1);

    String assignmentMessage = """
        {"eventId":"%s","eventType":"delivery.assigned","aggregateId":"%s","occurredAt":"2026-11-05T09:00:00Z",
         "payload":{"action":"ASSIGNED","oldCourierId":null,"newCourierId":"%s","actorSubject":"dispatcher-1"}}
        """.formatted(id, parcelId, courierId);

    consumer.onAssignmentEvent(assignmentMessage, "delivery.assigned", null);

    verify(processed).claim("tracking-reporting-service", id);
    verify(tracking).updateCourierAssignment(eq(parcelId), isNull(), eq(courierId.toString()), eq("ASSIGNED"));
    verify(processed).flush();
  }

  @Test
  void reassignmentEventUpdatesCourierAndRevokesOld() {
    UUID parcelId = UUID.randomUUID();
    UUID oldCourierId = UUID.randomUUID();
    UUID newCourierId = UUID.randomUUID();
    when(processed.claim(anyString(), eq(id))).thenReturn(1);

    String reassignmentMessage = """
        {"eventId":"%s","eventType":"delivery.reassigned","aggregateId":"%s","occurredAt":"2026-11-05T09:30:00Z",
         "payload":{"action":"REASSIGNED","oldCourierId":"%s","newCourierId":"%s","actorSubject":"dispatcher-1"}}
        """.formatted(id, parcelId, oldCourierId, newCourierId);

    consumer.onAssignmentEvent(reassignmentMessage, "delivery.reassigned", null);

    verify(processed).claim("tracking-reporting-service", id);
    verify(tracking).updateCourierAssignment(eq(parcelId), isNull(), eq(newCourierId.toString()), eq("REASSIGNED"));
  }

  @Test
  void cancelledAssignmentClearsCourier() {
    UUID parcelId = UUID.randomUUID();
    UUID oldCourierId = UUID.randomUUID();
    when(processed.claim(anyString(), eq(id))).thenReturn(1);

    String cancellationMessage = """
        {"eventId":"%s","eventType":"delivery.cancelled","aggregateId":"%s","occurredAt":"2026-11-05T10:00:00Z",
         "payload":{"action":"CANCELLED","oldCourierId":"%s","newCourierId":null,"actorSubject":"dispatcher-1"}}
        """.formatted(id, parcelId, oldCourierId);

    consumer.onAssignmentEvent(cancellationMessage, "delivery.cancelled", null);

    verify(processed).claim("tracking-reporting-service", id);
    verify(tracking).updateCourierAssignment(eq(parcelId), isNull(), isNull(), eq("CANCELLED"));
  }
}
