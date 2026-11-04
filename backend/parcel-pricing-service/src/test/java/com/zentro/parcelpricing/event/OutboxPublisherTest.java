package com.zentro.parcelpricing.event;

import com.zentro.parcelpricing.repository.OutboxRepository;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zentro.parcelpricing.config.OutboxProperties;
import com.zentro.parcelpricing.repository.OutboxRepository.PendingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionOperations;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {
  private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
  private static final String STATUS = "parcel.status.updated";
  private static final String CREATED = "parcel.created";
  private static final String SECRET = "{\"payload\":{\"marker\":\"PAYLOAD-MUST-NOT-BE-LOGGED\"}}";
  private static final UUID PARCEL_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
  private static final UUID PARCEL_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

  @Mock OutboxRepository repository;
  @Mock KafkaTemplate<String, String> kafka;

  SimpleMeterRegistry registry;
  OutboxPublisher publisher;
  ListAppender<ILoggingEvent> logs;

  static OutboxProperties properties(long sendTimeoutMs) {
    return new OutboxProperties(true, 1000, 50, 10, sendTimeoutMs, 1000, 300_000, 300_000, 3, (short) 1,
        Map.of(STATUS, STATUS, CREATED, CREATED));
  }

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    OutboxProperties props = properties(200);
    publisher = new OutboxPublisher(repository, kafka, props, new OutboxMetrics(registry, repository, props),
        TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
    logs = new ListAppender<>();
    logs.start();
    ((Logger) LoggerFactory.getLogger(OutboxPublisher.class)).addAppender(logs);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(OutboxPublisher.class)).detachAppender(logs);
  }

  private static PendingEvent event(UUID parcel, String type, int attempts) {
    return new PendingEvent(UUID.randomUUID(), parcel, type, SECRET, attempts);
  }

  private void batch(PendingEvent... events) {
    when(repository.fetchBatch(10, 50)).thenReturn(List.of(events));
  }

  private void sendSucceeds() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
  }

  private static String header(ProducerRecord<String, String> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private double counter(String name, String type) {
    var c = registry.find(name).tag("eventType", type).counter();
    return c == null ? 0 : c.count();
  }

  private void assertNoPayloadInLogs() {
    assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
        .noneMatch(m -> m.contains("PAYLOAD-MUST-NOT-BE-LOGGED"));
  }

  @Test
  @DisplayName("A sent event goes to its topic keyed by parcel id, unchanged, with headers, and is marked published")
  @SuppressWarnings("unchecked")
  void successMarksPublished() {
    PendingEvent e = event(PARCEL_A, STATUS, 0);
    batch(e);
    sendSucceeds();

    assertThat(publisher.publishBatch()).isEqualTo(1);

    ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka).send(sent.capture());
    ProducerRecord<String, String> record = sent.getValue();
    assertThat(record.topic()).isEqualTo(STATUS);
    assertThat(record.key()).isEqualTo(PARCEL_A.toString());
    assertThat(record.value()).isEqualTo(SECRET);
    assertThat(header(record, "eventId")).isEqualTo(e.eventId().toString());
    assertThat(header(record, "eventType")).isEqualTo(STATUS);
    assertThat(header(record, "schemaVersion")).isEqualTo("1");
    verify(repository).markPublished(e.eventId());
    verify(repository, never()).markFailed(any(), anyString(), any());
    assertThat(counter(OutboxMetrics.PUBLISHED, STATUS)).isEqualTo(1);
  }

  @Test
  @DisplayName("A failed send increases attempts with exponential back-off and records the error, never the payload")
  void failureBacksOff() {
    PendingEvent e = event(PARCEL_A, STATUS, 2);
    batch(e);
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("Topic parcel.status.updated not present")));
    when(repository.markFailed(any(), anyString(), any())).thenReturn(3);

    assertThat(publisher.publishBatch()).isZero();

    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<OffsetDateTime> next = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(repository).markFailed(eq(e.eventId()), error.capture(), next.capture());
    assertThat(error.getValue()).startsWith("TimeoutException").doesNotContain("PAYLOAD-MUST-NOT-BE-LOGGED");
    assertThat(next.getValue().toInstant()).isEqualTo(NOW.plusSeconds(4)); // 1s * 2^2
    verify(repository, never()).markPublished(any());
    assertThat(counter(OutboxMetrics.FAILED, STATUS)).isEqualTo(1);
    assertThat(logs.list).anySatisfy(l -> {
      assertThat(l.getLevel()).isEqualTo(Level.WARN);
      assertThat(l.getFormattedMessage()).contains(e.eventId().toString(), STATUS, "attempt 3");
    });
    assertNoPayloadInLogs();
  }

  @Test
  @DisplayName("A broker that does not answer within the send timeout counts as a failure")
  void sendTimeoutIsAFailure() {
    PendingEvent e = event(PARCEL_A, STATUS, 0);
    batch(e);
    when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
    when(repository.markFailed(any(), anyString(), any())).thenReturn(1);

    publisher.publishBatch();

    ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
    verify(repository).markFailed(eq(e.eventId()), error.capture(), any());
    assertThat(error.getValue()).contains("TimeoutException");
  }

  @Test
  @DisplayName("After a failed send the round stops, so later events are not sent ahead of it")
  void failureStopsTheRound() {
    PendingEvent first = event(PARCEL_A, STATUS, 0);
    PendingEvent second = event(PARCEL_B, CREATED, 0);
    batch(first, second);
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker down")));
    when(repository.markFailed(any(), anyString(), any())).thenReturn(1);

    publisher.publishBatch();

    verify(kafka, times(1)).send(any(ProducerRecord.class));
    verify(repository, never()).markFailed(eq(second.eventId()), anyString(), any());
    verify(repository, never()).markPublished(any());
  }

  @Test
  @DisplayName("An unmapped event type fails safely and holds back later events of the same parcel only")
  void unmappedTypeHoldsBackSameParcel() {
    PendingEvent unmapped = event(PARCEL_A, "parcel.unknown", 0);
    PendingEvent sameParcelLater = event(PARCEL_A, STATUS, 0);
    PendingEvent otherParcel = event(PARCEL_B, STATUS, 0);
    batch(unmapped, sameParcelLater, otherParcel);
    when(repository.markFailed(any(), anyString(), any())).thenReturn(1);
    sendSucceeds();

    assertThat(publisher.publishBatch()).isEqualTo(1);

    verify(repository).markFailed(eq(unmapped.eventId()), eq("unmapped event type"), any());
    verify(repository, never()).markPublished(sameParcelLater.eventId());
    verify(repository, never()).markFailed(eq(sameParcelLater.eventId()), anyString(), any());
    verify(repository).markPublished(otherParcel.eventId());
    verify(kafka, times(1)).send(any(ProducerRecord.class));
  }

  @Test
  @DisplayName("An event that reaches the attempt limit is logged as dead at ERROR level")
  void deadEventIsLoggedAsError() {
    PendingEvent e = event(PARCEL_A, STATUS, 9);
    batch(e);
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker down")));
    when(repository.markFailed(any(), anyString(), any())).thenReturn(10);

    publisher.publishBatch();

    assertThat(logs.list).anySatisfy(l -> {
      assertThat(l.getLevel()).isEqualTo(Level.ERROR);
      assertThat(l.getFormattedMessage()).contains(e.eventId().toString(), "dead", "10 attempts");
    });
    assertNoPayloadInLogs();
  }

  @Test
  @DisplayName("Events are sent in the order they were read")
  @SuppressWarnings("unchecked")
  void eventsAreSentInOrder() {
    PendingEvent first = event(PARCEL_A, CREATED, 0);
    PendingEvent second = event(PARCEL_A, STATUS, 0);
    batch(first, second);
    sendSucceeds();

    assertThat(publisher.publishBatch()).isEqualTo(2);

    ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka, times(2)).send(sent.capture());
    assertThat(sent.getAllValues()).extracting(r -> header(r, "eventId"))
        .containsExactly(first.eventId().toString(), second.eventId().toString());
  }

  @Test
  @DisplayName("A failing round (for example the database is down) is logged and does not escape the scheduler")
  void roundFailureIsContained() {
    when(repository.fetchBatch(anyInt(), anyInt())).thenThrow(new QueryTimeoutException("db down"));

    publisher.publishPending();

    assertThat(logs.list).anySatisfy(l -> assertThat(l.getFormattedMessage()).contains("QueryTimeoutException"));
  }

  @Test
  @DisplayName("Retry delay doubles per attempt and is capped")
  void retryDelay() {
    OutboxProperties props = properties(200);
    assertThat(props.retryDelay(0)).isEqualTo(Duration.ofSeconds(1));
    assertThat(props.retryDelay(3)).isEqualTo(Duration.ofSeconds(8));
    assertThat(props.retryDelay(9)).isEqualTo(Duration.ofMillis(300_000));
    assertThat(props.retryDelay(60)).isEqualTo(Duration.ofMillis(300_000));
  }
}
