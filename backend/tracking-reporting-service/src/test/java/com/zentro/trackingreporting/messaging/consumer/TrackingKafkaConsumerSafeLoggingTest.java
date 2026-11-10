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
  @Test void malformedAndInvalidValuesNeverAppearInLogs(CapturedOutput output) {
    var processed = mock(ProcessedEventRepository.class);
    var tracking = mock(TrackingService.class);
    var projections = mock(DeliveryProjectionRepository.class);
    var consumer = new TrackingKafkaConsumer(tracking, processed, new ObjectMapper(), projections);
    String secret = "synthetic-private-token-person@example.test";
    for (String value : new String[]{"{\"" + secret + "\":", "{\"eventId\":\"" + secret + "\"}", "\"" + secret + "\""})
      consumer.onParcelEvent(value, "parcel.created", secret);
    assertThat(output.getAll()).contains("category=invalid_contract").doesNotContain(secret);
    verifyNoInteractions(processed, tracking, projections);
  }
}
