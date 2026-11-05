package com.zentro.parcelpricing.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.zentro.parcelpricing.outbox.publisher.OutboxMetrics;
import com.zentro.parcelpricing.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class OutboxHealthIndicatorTest {
  private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
  private static final OutboxProperties PROPS = new OutboxProperties(true, 1000, 50, 10, 5000, 1000, 300_000,
      300_000, 3, (short) 1, Map.of("parcel.status.updated", "parcel.status.updated"));

  @Mock OutboxRepository repository;
  OutboxHealthIndicator indicator;

  @BeforeEach
  void setUp() {
    indicator = new OutboxHealthIndicator(repository, PROPS, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void state(long pending, long dead, Long oldestAgeSeconds) {
    when(repository.countPending(10)).thenReturn(pending);
    when(repository.countDead(10)).thenReturn(dead);
    when(repository.oldestPendingAt(10)).thenReturn(oldestAgeSeconds == null ? Optional.empty()
        : Optional.of(OffsetDateTime.ofInstant(NOW.minusSeconds(oldestAgeSeconds), ZoneOffset.UTC)));
  }

  @Test
  @DisplayName("Nothing pending: UP with zero counts")
  void emptyOutboxIsUp() {
    state(0, 0, null);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("pending", 0L).containsEntry("dead", 0L)
        .containsEntry("oldestPendingAgeSeconds", 0L);
  }

  @Test
  @DisplayName("Recent pending events: still UP")
  void recentPendingIsUp() {
    state(3, 0, 20L);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("pending", 3L).containsEntry("oldestPendingAgeSeconds", 20L);
  }

  @Test
  @DisplayName("Oldest pending event older than the stuck threshold: DOWN")
  void stuckPendingIsDown() {
    state(5, 0, 301L);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("oldestPendingAgeSeconds", 301L).containsKey("reason");
  }

  @Test
  @DisplayName("Any dead event: DOWN with the dead count")
  void deadEventsAreDown() {
    state(0, 2, null);

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("dead", 2L).containsKey("reason");
  }

  @Test
  @DisplayName("Database error: DOWN with the error type only")
  void databaseErrorIsDown() {
    when(repository.countPending(10)).thenThrow(new QueryTimeoutException("password=secret host=db"));

    Health health = indicator.health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails()).containsEntry("error", "QueryTimeoutException");
    assertThat(health.getDetails().toString()).doesNotContain("secret");
  }

  @Test
  @DisplayName("Metrics: counters per event type and gauges for pending and dead events")
  void metrics() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(repository.countPending(10)).thenReturn(4L);
    when(repository.countDead(10)).thenReturn(1L);
    OutboxMetrics metrics = new OutboxMetrics(registry, repository, PROPS);

    metrics.published("parcel.status.updated");
    metrics.published("parcel.status.updated");
    metrics.published("parcel.created");
    metrics.failed("parcel.status.updated");

    assertThat(registry.get(OutboxMetrics.PUBLISHED).tag("eventType", "parcel.status.updated").counter().count())
        .isEqualTo(2);
    assertThat(registry.get(OutboxMetrics.PUBLISHED).tag("eventType", "parcel.created").counter().count())
        .isEqualTo(1);
    assertThat(registry.get(OutboxMetrics.FAILED).tag("eventType", "parcel.status.updated").counter().count())
        .isEqualTo(1);
    assertThat(registry.get(OutboxMetrics.PENDING).gauge().value()).isEqualTo(4);
    assertThat(registry.get(OutboxMetrics.DEAD).gauge().value()).isEqualTo(1);
  }

  @Test
  @DisplayName("Metrics: a gauge reports NaN instead of failing when the database cannot be read")
  void gaugeSurvivesDatabaseError() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    when(repository.countPending(10)).thenThrow(new QueryTimeoutException("db down"));
    new OutboxMetrics(registry, repository, PROPS);

    assertThat(registry.get(OutboxMetrics.PENDING).gauge().value()).isNaN();
  }
}
