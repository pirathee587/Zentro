package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.notificationintegration.config.NotificationHealthIndicator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The mock sender always fails: the notification ends FAILED after the attempt limit and health turns DOWN. */
@TestPropertySource(properties = {
    "zentro.notifications.mock.fail-mode=always",
    "zentro.notifications.retry.max-attempts=3"
})
class NotificationFailureIntegrationTest extends AbstractNotificationIntegrationTest {
  @Autowired NotificationHealthIndicator health;
  @Autowired HealthContributorRegistry healthContributors;
  @Autowired MockMvc mvc;

  @Test
  @DisplayName("Every attempt failing ends in FAILED after the maximum, with the error recorded and health DOWN")
  void failedAfterMaxAttempts() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(eventId, parcelId, newTrackingRef(), "DELIVERED", null));

    Map<String, Object> row = awaitRow(eventId, "FAILED");
    assertThat(row.get("attempts")).isEqualTo(3);
    assertThat((String) row.get("last_error")).startsWith("NotificationSendException");
    assertThat(row.get("next_attempt_at")).isNull();

    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.health().getDetails()).containsKey("failedRecently");
    assertThat(healthContributors.getContributor("notifications")).isSameAs(health);
    mvc.perform(get("/actuator/health")).andExpect(status().isServiceUnavailable());
  }
}
