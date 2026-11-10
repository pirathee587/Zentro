package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** The mock sender fails the first attempt of every notification; the retry scheduler then sends it. */
@TestPropertySource(properties = "zentro.notifications.mock.fail-mode=first-attempt")
class NotificationRetryIntegrationTest extends AbstractNotificationIntegrationTest {
  @Autowired MeterRegistry registry;

  @Test
  @DisplayName("A failed first attempt is retried with back-off and then SENT")
  void retriedThenSent() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(eventId, parcelId, newTrackingRef(), "PICKED_UP", null));

    Map<String, Object> row = awaitRow(eventId, "SENT");
    assertThat(row.get("attempts")).isEqualTo(2);
    assertThat(row.get("next_attempt_at")).isNull();
    var history = jdbc.queryForList("SELECT outcome, terminal, next_retry_at FROM notification.delivery_attempt_history WHERE event_id = ? ORDER BY attempt_number", eventId);
    assertThat(history).extracting(r -> r.get("outcome")).containsExactly("RETRY", "SENT");
    assertThat(history.get(0)).containsEntry("terminal", false);
    assertThat(history.get(0).get("next_retry_at")).isNotNull();
    assertThat(history.get(1)).containsEntry("terminal", true).containsEntry("next_retry_at", null);
    assertThat(registry.counter("zentro.notifications.retried").count()).isGreaterThanOrEqualTo(1);
    assertThat(registry.counter("zentro.notifications.sent").count()).isGreaterThanOrEqualTo(1);
  }
}
