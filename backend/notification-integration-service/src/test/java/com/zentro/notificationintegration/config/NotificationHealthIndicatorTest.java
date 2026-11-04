package com.zentro.notificationintegration.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.zentro.notificationintegration.service.NotificationMetrics;
import com.zentro.notificationintegration.service.NotificationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class NotificationHealthIndicatorTest {
  private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
  private static final NotificationProperties PROPS = new NotificationProperties(
      new NotificationProperties.Consumer(true), new NotificationProperties.Retry(5, 5000, 5000, 600_000),
      new NotificationProperties.Mock(NotificationProperties.FailMode.NONE), new NotificationProperties.Health(60, 10));

  @Mock NotificationRepository repository;

  private Health health(long pending, long failedRecently, Long oldestAgeSeconds) {
    when(repository.countOpen()).thenReturn(pending);
    when(repository.countFailedSince(any())).thenReturn(failedRecently);
    when(repository.oldestOpenCreatedAt()).thenReturn(oldestAgeSeconds == null ? Optional.empty()
        : Optional.of(OffsetDateTime.ofInstant(NOW.minusSeconds(oldestAgeSeconds), ZoneOffset.UTC)));
    return new NotificationHealthIndicator(repository, PROPS, Clock.fixed(NOW, ZoneOffset.UTC)).health();
  }

  @Test
  @DisplayName("Nothing waiting and nothing failed: UP")
  void idleIsUp() {
    Health h = health(0, 0, null);
    assertThat(h.getStatus()).isEqualTo(Status.UP);
    assertThat(h.getDetails()).containsEntry("pending", 0L).containsEntry("failedRecently", 0L);
  }

  @Test
  @DisplayName("Recent pending notifications: still UP")
  void recentPendingIsUp() {
    assertThat(health(2, 0, 30L).getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("A notification waiting longer than 10 minutes: DOWN")
  void stuckIsDown() {
    Health h = health(1, 0, 601L);
    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    assertThat(h.getDetails()).containsKey("reason").containsEntry("oldestPendingAgeSeconds", 601L);
  }

  @Test
  @DisplayName("A FAILED notification in the last hour: DOWN")
  void recentFailureIsDown() {
    Health h = health(0, 1, null);
    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    assertThat(h.getDetails()).containsEntry("failedRecently", 1L).containsKey("reason");
  }

  @Test
  @DisplayName("Database error: DOWN with the error type only")
  void databaseErrorIsDown() {
    when(repository.countOpen()).thenThrow(new QueryTimeoutException("password=secret"));
    Health h = new NotificationHealthIndicator(repository, PROPS, Clock.fixed(NOW, ZoneOffset.UTC)).health();
    assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    assertThat(h.getDetails()).containsEntry("error", "QueryTimeoutException");
    assertThat(h.getDetails().toString()).doesNotContain("secret");
  }

  @Test
  @DisplayName("Metrics: counters move and gauges read pending and failed rows")
  void metrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(repository.countOpen()).thenReturn(3L);
    when(repository.countFailed()).thenReturn(1L);
    NotificationMetrics metrics = new NotificationMetrics(registry, repository);

    metrics.sent();
    metrics.sent();
    metrics.failed();
    metrics.retried();
    metrics.skipped();
    metrics.duplicate();
    metrics.rejected();

    assertThat(registry.get(NotificationMetrics.SENT).counter().count()).isEqualTo(2);
    assertThat(registry.get(NotificationMetrics.FAILED).counter().count()).isEqualTo(1);
    assertThat(registry.get(NotificationMetrics.RETRIED).counter().count()).isEqualTo(1);
    assertThat(registry.get(NotificationMetrics.SKIPPED).counter().count()).isEqualTo(1);
    assertThat(registry.get(NotificationMetrics.DUPLICATES).counter().count()).isEqualTo(1);
    assertThat(registry.get(NotificationMetrics.REJECTED).counter().count()).isEqualTo(1);
    assertThat(registry.get(NotificationMetrics.PENDING_GAUGE).gauge().value()).isEqualTo(3);
    assertThat(registry.get(NotificationMetrics.FAILED_GAUGE).gauge().value()).isEqualTo(1);
  }
}
