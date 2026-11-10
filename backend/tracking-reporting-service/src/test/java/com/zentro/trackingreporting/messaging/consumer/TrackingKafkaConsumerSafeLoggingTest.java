package com.zentro.trackingreporting.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.reporting.DeliveryProjectionRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class TrackingKafkaConsumerSafeLoggingTest {
  private static final String SECRET = "private-token-person@example.test";
  private static final UUID EVENT_ID = UUID.fromString("ec805cc2-f4eb-4f38-9637-79170eb950d1");
  @Mock private TrackingService trackingService;
  @Mock private ProcessedEventRepository repository;
  private TrackingKafkaConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new TrackingKafkaConsumer(trackingService, repository, new ObjectMapper());
  }

  static Stream<Arguments> discardedEvents() {
    return Stream.of(
        Arguments.of("\"" + SECRET + "\"", null, "invalid_envelope"),
        Arguments.of("{\"eventId\":\"" + SECRET + "\"}", null, "invalid_event_id"),
        Arguments.of("{}", SECRET, "invalid_event_id"),
        Arguments.of("{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"parcel.created\",\"aggregateId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-11-04T12:00:00Z\",\"secret\":\"" + SECRET + "\"}",
            null, "missing_payload"),
        Arguments.of("{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"parcel.created\",\"aggregateId\":\"" + UUID.randomUUID() + "\",\"occurredAt\":\"2026-11-04T12:00:00Z\",\"payload\":{\"secret\":\"" + SECRET + "\"}}",
            null, "missing_tracking_ref"));
  }

  @ParameterizedTest
  @MethodSource("discardedEvents")
  void discardedInputsAreNotLogged(String message, String headerId, String category, CapturedOutput output) {
    consumer.onParcelEvent(message, "parcel.created", headerId);

    assertThat(output.getAll()).contains("category=" + category).doesNotContain(SECRET);
    verify(repository, never()).save(any());
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void malformedJsonDoesNotLogParserMessageOrSource(CapturedOutput output) {
    assertThatThrownBy(() -> consumer.onParcelEvent("{\"" + SECRET + "\":", "parcel.created", null))
        .isInstanceOf(RuntimeException.class)
        .hasCauseInstanceOf(com.fasterxml.jackson.core.JsonProcessingException.class);

    assertThat(output.getAll()).contains("category=processing_error").doesNotContain(SECRET, "JsonEOFException");
    verify(repository, never()).save(any());
  }

  @Test
  void fallbackAndSuccessLogsExcludeUnvalidatedValues(CapturedOutput output) {
    String malformed = """
        {"eventId":"%s", "eventType":"parcel.status.updated", "aggregateId":"%s", "occurredAt":"%s",
         "payload":{"trackingRef":"%s", "status":"DELIVERED"}}
        """.formatted(EVENT_ID, SECRET, SECRET, SECRET);

    consumer.onParcelEvent(malformed, "parcel.status.updated", null);

    assertThat(output.getAll()).contains("category=invalid_aggregate_id").doesNotContain(SECRET);
    verify(repository, never()).save(any());
    verify(trackingService, never()).recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

    UUID parcelId = UUID.randomUUID();
    String validWithSecret = """
        {"eventId":"%s", "eventType":"parcel.status.updated", "aggregateId":"%s",
         "occurredAt":"2026-11-04T12:00:00Z",
         "payload":{"trackingRef":"ZT-7K3QX9M2PA", "previousStatus":"OUT_FOR_DELIVERY", "status":"DELIVERED", "actorRole":"DISPATCHER", "unvalidatedField":"%s"}}
        """.formatted(EVENT_ID, parcelId, SECRET);

    consumer.onParcelEvent(validWithSecret, "parcel.status.updated", null);

    assertThat(output.getAll()).contains("Tracking event processed: eventId=" + EVENT_ID).doesNotContain(SECRET);
    verify(repository).save(any());
    verify(trackingService).recordEvent(org.mockito.ArgumentMatchers.eq("ZT-7K3QX9M2PA"), org.mockito.ArgumentMatchers.eq(parcelId),
        any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(EVENT_ID));
  }

  @Test
  void processingExceptionAndNestedCauseAreNotLoggedButRemainAvailableForRetry(CapturedOutput output) {
    IllegalStateException failure = new IllegalStateException("database-password=" + SECRET,
        new RuntimeException("nested-secret=" + SECRET));
    when(trackingService.recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);
    String message = """
        {"eventId":"%s", "eventType":"parcel.status.updated", "aggregateId":"%s",
         "occurredAt":"2026-11-04T12:00:00Z",
         "payload":{"trackingRef":"ZT-7K3QX9M2PA", "previousStatus":"OUT_FOR_DELIVERY", "status":"DELIVERED", "actorRole":"DISPATCHER"}}
        """.formatted(EVENT_ID, UUID.randomUUID());

    assertThatThrownBy(() -> consumer.onParcelEvent(message, "parcel.status.updated", null))
        .isInstanceOf(RuntimeException.class).hasCause(failure);

    assertThat(output.getAll()).contains("category=processing_error eventId=" + EVENT_ID)
        .doesNotContain(SECRET, "IllegalStateException", "nested-secret", "database-password");
    verify(repository, never()).save(any());
  }
}
