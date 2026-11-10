package com.zentro.trackingreporting.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
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
        Arguments.of("{\"eventId\":\"" + EVENT_ID + "\",\"secret\":\"" + SECRET + "\"}",
            null, "missing_payload"),
        Arguments.of("{\"eventId\":\"" + EVENT_ID + "\",\"payload\":{\"secret\":\"" + SECRET + "\"}}",
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
    String message = """
        {"eventId":"%s", "aggregateId":"%s", "occurredAt":"%s",
         "payload":{"trackingRef":"%s", "status":"DELIVERED"}}
        """.formatted(EVENT_ID, SECRET, SECRET, SECRET);

    consumer.onParcelEvent(message, "parcel.status.updated", null);

    assertThat(output.getAll()).contains("category=invalid_aggregate_id", "category=invalid_occurred_at",
        "Tracking event processed: eventId=" + EVENT_ID).doesNotContain(SECRET);
    verify(repository).save(any());
    verify(trackingService).recordEvent(org.mockito.ArgumentMatchers.eq(SECRET), org.mockito.ArgumentMatchers.isNull(),
        any(), any(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.eq(EVENT_ID));
  }

  @Test
  void processingExceptionAndNestedCauseAreNotLoggedButRemainAvailableForRetry(CapturedOutput output) {
    IllegalStateException failure = new IllegalStateException("database-password=" + SECRET,
        new RuntimeException("nested-secret=" + SECRET));
    when(trackingService.recordEvent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(failure);
    String message = """
        {"eventId":"%s", "payload":{"trackingRef":"%s", "status":"DELIVERED"}}
        """.formatted(EVENT_ID, SECRET);

    assertThatThrownBy(() -> consumer.onParcelEvent(message, "parcel.status.updated", null))
        .isInstanceOf(RuntimeException.class).hasCause(failure);

    assertThat(output.getAll()).contains("category=processing_error eventId=" + EVENT_ID)
        .doesNotContain(SECRET, "IllegalStateException", "nested-secret", "database-password");
    verify(repository, never()).save(any());
  }
}
