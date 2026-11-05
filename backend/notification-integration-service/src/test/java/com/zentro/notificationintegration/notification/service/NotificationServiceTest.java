package com.zentro.notificationintegration.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zentro.notificationintegration.config.NotificationProperties.FailMode;
import com.zentro.notificationintegration.delivery.sms.MockSmsSender;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.notification.entity.NotificationStatus;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.notification.template.NotificationMessages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
  private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

  @Mock NotificationRepository repository;
  SimpleMeterRegistry registry;
  NotificationService service;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    service = new NotificationService(repository, new NotificationMessages(), new MockSmsSender(FailMode.NONE),
        new NotificationMetrics(registry, repository), Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static ParcelEvent event(String status, String reason) {
    return new ParcelEvent(UUID.randomUUID(), "parcel.status.updated", UUID.randomUUID(),
        OffsetDateTime.parse("2026-11-05T09:59:59Z"), "ZT-ABCDEFGH12", status, reason);
  }

  private double counter(String name) {
    return registry.get(name).counter().count();
  }

  @Test
  @DisplayName("A new notifying event is stored as PENDING, due now, and returned for sending")
  void newEventIsPending() {
    ParcelEvent event = event("PICKED_UP", null);
    UUID id = UUID.randomUUID();
    when(repository.markProcessed(NotificationService.CONSUMER, event.eventId())).thenReturn(true);
    when(repository.insertAttempt(eq(event), eq("MOCK_SMS"), anyString(), eq(NotificationStatus.PENDING), any()))
        .thenReturn(id);

    assertThat(service.handle(event)).contains(id);

    verify(repository).insertAttempt(event, "MOCK_SMS", "Your parcel ZT-ABCDEFGH12 has been picked up.",
        NotificationStatus.PENDING, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
  }

  @Test
  @DisplayName("IN_TRANSIT is stored as SKIPPED and nothing is sent")
  void inTransitIsSkipped() {
    ParcelEvent event = event("IN_TRANSIT", null);
    when(repository.markProcessed(NotificationService.CONSUMER, event.eventId())).thenReturn(true);

    assertThat(service.handle(event)).isEmpty();

    verify(repository).insertAttempt(eq(event), eq("MOCK_SMS"), eq("No notification for status IN_TRANSIT"),
        eq(NotificationStatus.SKIPPED), isNull());
    assertThat(counter(NotificationMetrics.SKIPPED)).isEqualTo(1);
  }

  @Test
  @DisplayName("A duplicate eventId changes nothing and is counted")
  void duplicateDoesNothing() {
    ParcelEvent event = event("PICKED_UP", null);
    when(repository.markProcessed(NotificationService.CONSUMER, event.eventId())).thenReturn(false);

    assertThat(service.handle(event)).isEmpty();

    verify(repository, never()).insertAttempt(any(), anyString(), anyString(), any(), any());
    assertThat(counter(NotificationMetrics.DUPLICATES)).isEqualTo(1);
  }

  @Test
  @DisplayName("A malformed message is recorded by position and reason and counted")
  void rejectRecordsReason() {
    service.reject("parcel.status.updated", 2, 41L, "missing eventId");

    verify(repository).insertRejected("parcel.status.updated", 2, 41L, "missing eventId");
    assertThat(counter(NotificationMetrics.REJECTED)).isEqualTo(1);
  }

  @Test
  @DisplayName("Template validation logs variable names but no event content")
  void validationLogContainsNoPersonalOrRawValues() {
    Logger logger = (Logger) LoggerFactory.getLogger(NotificationService.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();
    logs.start();
    logger.addAppender(logs);
    ParcelEvent invalid = new ParcelEvent(UUID.randomUUID(), "parcel.status.updated", UUID.randomUUID(),
        OffsetDateTime.parse("2026-11-05T09:59:59Z"), "PRIVATE-TRACKING", "FAILED", "PRIVATE-FAILURE-REASON");
    when(repository.markProcessed(NotificationService.CONSUMER, invalid.eventId())).thenReturn(true);
    try {
      service.handle(invalid);
      String output = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + b);
      assertThat(output).contains("trackingRef");
      assertThat(output).doesNotContain("PRIVATE-TRACKING", "PRIVATE-FAILURE-REASON");
    } finally {
      logger.detachAppender(logs);
    }
  }

  @Test
  @DisplayName("handle and reject run in a transaction")
  void transactional() throws Exception {
    assertThat(NotificationService.class.getMethod("handle", ParcelEvent.class)
        .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    assertThat(NotificationService.class.getMethod("reject", String.class, Integer.class, Long.class, String.class)
        .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    assertThat(Optional.empty()).isEmpty();
  }
}
