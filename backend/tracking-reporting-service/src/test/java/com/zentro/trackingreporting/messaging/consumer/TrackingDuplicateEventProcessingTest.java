package com.zentro.trackingreporting.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.reporting.DeliveryProjectionRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * G04/G37 Coverage:
 * Comprehensive tests verifying idempotent processing of duplicate Kafka tracking events.
 * Asserts that duplicate replays, re-deliveries, and already-claimed event IDs do not
 * create duplicate tracking events, do not corrupt projections, and exit safely.
 */
@ExtendWith(MockitoExtension.class)
class TrackingDuplicateEventProcessingTest {

  @Mock private TrackingService trackingService;
  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private DeliveryProjectionRepository deliveryProjectionRepository;

  private TrackingKafkaConsumer consumer;
  private final UUID eventId = UUID.randomUUID();
  private final UUID parcelId = UUID.randomUUID();
  private final String trackingRef = "ZT-7K3QX9M2PA";

  @BeforeEach
  void setUp() {
    consumer = new TrackingKafkaConsumer(
        trackingService,
        processedEventRepository,
        new ObjectMapper(),
        deliveryProjectionRepository
    );
  }

  private String createEventJson(UUID eId, String status) {
    return """
        {
          "eventId": "%s",
          "eventType": "parcel.created",
          "aggregateId": "%s",
          "occurredAt": "2026-11-10T10:00:00Z",
          "payload": {
            "trackingRef": "%s",
            "status": "%s",
            "source": "CUSTOMER_BOOKING",
            "occurredAt": "2026-11-10T10:00:00Z"
          }
        }
        """.formatted(eId, parcelId, trackingRef, status);
  }

  @Test
  @DisplayName("Initial event delivery processes normally when claim succeeds")
  void initialDeliveryProcessesNormally() {
    when(processedEventRepository.claim(eq("tracking-reporting-service"), eq(eventId))).thenReturn(1);

    consumer.onParcelEvent(createEventJson(eventId, "CREATED"), "parcel.created", eventId.toString());

    verify(processedEventRepository).claim("tracking-reporting-service", eventId);
    verify(deliveryProjectionRepository).lock(parcelId);
    verify(trackingService).recordEvent(eq(trackingRef), eq(parcelId), any(), any(), any(), any(), any(), any(), any(), any(), eq(eventId));
    verify(processedEventRepository).flush();
    verify(deliveryProjectionRepository).refresh(parcelId);
  }

  @Test
  @DisplayName("Duplicate event replay is completely skipped when claim returns 0")
  void duplicateEventReplayIsSkipped() {
    // When claim returns 0 (already processed / in-flight)
    when(processedEventRepository.claim(eq("tracking-reporting-service"), eq(eventId))).thenReturn(0);

    consumer.onParcelEvent(createEventJson(eventId, "CREATED"), "parcel.created", eventId.toString());

    // Claim was checked
    verify(processedEventRepository).claim("tracking-reporting-service", eventId);

    // But no tracking or projection actions were taken
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    verify(deliveryProjectionRepository, never()).lock(any());
    verify(deliveryProjectionRepository, never()).refresh(any());
  }

  @Test
  @DisplayName("Sequential duplicate messages: first is processed, second is skipped idempotently")
  void sequentialDuplicateMessagesHandledIdempotently() {
    // First call claims (returns 1), second call fails claim (returns 0)
    when(processedEventRepository.claim(eq("tracking-reporting-service"), eq(eventId)))
        .thenReturn(1)
        .thenReturn(0);

    String message = createEventJson(eventId, "CREATED");

    // First delivery
    consumer.onParcelEvent(message, "parcel.created", null);

    // Replay of same message
    consumer.onParcelEvent(message, "parcel.created", null);

    // Verified: recordEvent and refresh called exactly ONCE
    verify(trackingService, times(1)).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(eventId));
    verify(deliveryProjectionRepository, times(1)).refresh(parcelId);
    verify(processedEventRepository, times(2)).claim("tracking-reporting-service", eventId);
  }

  @Test
  @DisplayName("Duplicate event with conflicting header eventId is discarded before claim")
  void conflictingHeaderEventIdDiscardedBeforeClaim() {
    UUID headerId = UUID.randomUUID(); // different from payload eventId
    String message = createEventJson(eventId, "CREATED");

    consumer.onParcelEvent(message, "parcel.created", headerId.toString());

    verifyNoInteractions(processedEventRepository, trackingService, deliveryProjectionRepository);
  }
}
