package com.zentro.notificationintegration.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zentro.notificationintegration.config.NotificationProperties;
import com.zentro.notificationintegration.config.NotificationProperties.FailMode;
import com.zentro.notificationintegration.delivery.sms.MockSmsSender;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.notification.repository.NotificationRepository.AttemptRow;
import com.zentro.notificationintegration.notification.service.NotificationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class NotificationDispatcherTest {
  private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
  private static final String TEXT = "Delivery of parcel ZT-ABCDEFGH12 was not completed: Gate locked.";
  private static final UUID ID = UUID.randomUUID();

  @Mock NotificationRepository repository;
  SimpleMeterRegistry registry;
  ListAppender<ILoggingEvent> logs;

  static NotificationProperties properties(int maxAttempts) {
    return new NotificationProperties(new NotificationProperties.Consumer(true),
        new NotificationProperties.Retry(maxAttempts, 5000, 5000, 600_000),
        new NotificationProperties.Mock(FailMode.NONE), new NotificationProperties.Health(60, 10));
  }

  private NotificationDispatcher dispatcher(FailMode mode, int maxAttempts) {
    return new NotificationDispatcher(repository, new MockSmsSender(mode),
        new NotificationMetrics(registry, repository), properties(maxAttempts), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private void row(int attempts) {
    when(repository.lockForDelivery(ID)).thenReturn(Optional.of(
        new AttemptRow(ID, UUID.randomUUID(), "ZT-ABCDEFGH12", "FAILED", "MOCK_SMS", TEXT, attempts)));
  }

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(NotificationDispatcher.class)).addAppender(logs);
    ((Logger) LoggerFactory.getLogger(MockSmsSender.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(NotificationDispatcher.class)).detachAppender(logs);
    ((Logger) LoggerFactory.getLogger(MockSmsSender.class)).detachAppender(logs);
  }

  private void assertNoMessageTextInLogs() {
    assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(m -> m.contains("Gate locked") || m.contains(TEXT));
  }

  @Test
  @DisplayName("A successful send marks the notification SENT with the attempt count")
  void successIsSent() {
    row(0);

    dispatcher(FailMode.NONE, 5).deliver(ID);

    verify(repository).markSent(ID, 1);
    assertThat(registry.get(NotificationMetrics.SENT).counter().count()).isEqualTo(1);
    assertNoMessageTextInLogs();
  }

  @Test
  @DisplayName("A failed send becomes RETRY with an error and an exponential next attempt time")
  void failureIsRetried() {
    row(2);

    dispatcher(FailMode.ALWAYS, 5).deliver(ID);

    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repository).markRetry(eq(ID), eq(3), error.capture(), next.capture());
    assertThat(error.getValue()).startsWith("NotificationSendException").doesNotContain("Gate locked");
    assertThat(next.getValue().toInstant()).isEqualTo(NOW.plusSeconds(20)); // 5s * 2^(3-1)
    verify(repository, never()).markSent(any(), anyInt());
    assertThat(registry.get(NotificationMetrics.RETRIED).counter().count()).isEqualTo(1);
    assertThat(logs.list).anySatisfy(l -> assertThat(l.getLevel()).isEqualTo(Level.WARN));
    assertNoMessageTextInLogs();
  }

  @Test
  @DisplayName("A retry after recovery is SENT (first-attempt fail mode)")
  void retryAfterRecoveryIsSent() {
    row(1);

    dispatcher(FailMode.FIRST_ATTEMPT, 5).deliver(ID);

    verify(repository).markSent(ID, 2);
  }

  @Test
  @DisplayName("The last allowed attempt failing makes the notification FAILED with an ERROR log")
  void maxAttemptsIsFailed() {
    row(4);

    dispatcher(FailMode.ALWAYS, 5).deliver(ID);

    verify(repository).markFailed(eq(ID), eq(5), anyString());
    verify(repository, never()).markRetry(any(), anyInt(), anyString(), any());
    assertThat(registry.get(NotificationMetrics.FAILED).counter().count()).isEqualTo(1);
    assertThat(logs.list).anySatisfy(l -> {
      assertThat(l.getLevel()).isEqualTo(Level.ERROR);
      assertThat(l.getFormattedMessage()).contains("5 attempts", "ZT-ABCDEFGH12");
    });
    assertNoMessageTextInLogs();
  }

  @Test
  @DisplayName("A notification that is final or locked elsewhere is left alone")
  void finalOrLockedIsIgnored() {
    when(repository.lockForDelivery(ID)).thenReturn(Optional.empty());

    dispatcher(FailMode.NONE, 5).deliver(ID);

    verify(repository, never()).markSent(any(), anyInt());
    verify(repository, never()).markRetry(any(), anyInt(), anyString(), any());
  }

  @Test
  @DisplayName("The retry scheduler sends every due notification and survives a failing one")
  void schedulerSendsDue() {
    UUID other = UUID.randomUUID();
    when(repository.findDue(NotificationRetryScheduler.BATCH_SIZE)).thenReturn(List.of(other, ID));
    when(repository.lockForDelivery(other)).thenThrow(new org.springframework.dao.QueryTimeoutException("db"));
    row(1);

    new NotificationRetryScheduler(repository, dispatcher(FailMode.NONE, 5)).retryDue();

    verify(repository).markSent(ID, 2);
  }

  @Test
  @DisplayName("Back-off doubles per attempt and is capped at 10 minutes")
  void backOff() {
    NotificationProperties.Retry retry = properties(5).retry();
    assertThat(retry.delayAfter(1)).isEqualTo(Duration.ofSeconds(5));
    assertThat(retry.delayAfter(2)).isEqualTo(Duration.ofSeconds(10));
    assertThat(retry.delayAfter(4)).isEqualTo(Duration.ofSeconds(40));
    assertThat(retry.delayAfter(20)).isEqualTo(Duration.ofMinutes(10));
  }
}
