package com.zentro.notificationintegration.webhook.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zentro.notificationintegration.webhook.repository.WebhookDeliveryRepository;
import com.zentro.notificationintegration.webhook.service.WebhookDeliveryService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.DataAccessResourceFailureException;

class WebhookHealthIndicatorTest {
  private static final Instant NOW = Instant.parse("2026-11-06T08:00:00Z");
  private final WebhookDeliveryRepository repository = mock(WebhookDeliveryRepository.class);
  private final WebhookHealthIndicator indicator = new WebhookHealthIndicator(repository,
      new WebhookProperties(true, false, "", "merchant-webhooks", new WebhookProperties.Retry(8, 5000, 10000, 1800000),
          new WebhookProperties.Http(2000, 5000), new WebhookProperties.Health(60, 10)),
      Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  @DisplayName("UP with counts when nothing failed recently and nothing is overdue")
  void up() {
    when(repository.countOpen()).thenReturn(2L);
    when(repository.oldestDueAt()).thenReturn(Optional.of(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(1)));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("pending", 2L).containsEntry("failedRecently", 0L)
        .containsEntry("oldestOverdueSeconds", 60L).containsEntry("enabled", true);
  }

  @Test
  @DisplayName("DOWN when a delivery failed in the last hour (disabled endpoints are excluded by the query)")
  void downOnRecentFailure() {
    when(repository.countFailedSince(eq(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(60)),
        eq(WebhookDeliveryService.DISABLED_ERROR))).thenReturn(1L);
    when(repository.oldestDueAt()).thenReturn(Optional.empty());

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails().get("reason").toString()).contains("failed permanently");
  }

  @Test
  @DisplayName("DOWN when an open delivery is overdue by more than 10 minutes")
  void downWhenStuck() {
    when(repository.oldestDueAt()).thenReturn(Optional.of(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(11)));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails().get("reason").toString()).contains("overdue");
  }

  @Test
  @DisplayName("DOWN with only the error type when the database cannot be read")
  void downOnDatabaseError() {
    when(repository.countOpen()).thenThrow(new DataAccessResourceFailureException("connection refused password=x"));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("error", "DataAccessResourceFailureException");
    assertThat(health.getDetails().toString()).doesNotContain("password");
  }

  @Test
  @DisplayName("Without open deliveries the overdue time is zero")
  void noneOpen() {
    when(repository.oldestDueAt()).thenReturn(Optional.empty());
    when(repository.countFailedSince(any(), any())).thenReturn(0L);

    assertThat(indicator.health().getDetails()).containsEntry("oldestOverdueSeconds", 0L);
  }
}
