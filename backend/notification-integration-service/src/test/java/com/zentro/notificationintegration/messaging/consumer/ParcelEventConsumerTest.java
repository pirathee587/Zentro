package com.zentro.notificationintegration.messaging.consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.notificationintegration.delivery.service.NotificationDispatcher;
import com.zentro.notificationintegration.messaging.event.MalformedEventException;
import com.zentro.notificationintegration.messaging.event.ParcelEvent;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import com.zentro.notificationintegration.notification.service.NotificationService;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class ParcelEventConsumerTest {
  private static final String TOPIC = ParcelEventParser.PARCEL_STATUS_UPDATED;
  private static final String PAYLOAD = "{\"eventId\":\"6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10\"}";

  @Mock ParcelEventParser parser;
  @Mock NotificationService service;
  @Mock NotificationDispatcher dispatcher;
  @Mock Acknowledgment ack;

  ParcelEventConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new ParcelEventConsumer(parser, service, dispatcher);
  }

  private static ConsumerRecord<String, String> record(String value) {
    return new ConsumerRecord<>(TOPIC, 1, 42L, "parcel-key", value);
  }

  private static ParcelEvent validEvent() {
    return new ParcelEvent(
        UUID.randomUUID(),
        TOPIC,
        UUID.randomUUID(),
        OffsetDateTime.parse("2026-11-05T10:15:30Z"),
        "ZT-ABCDEFGH12",
        "PICKED_UP",
        null);
  }

  @Test
  @DisplayName("Valid notifying event is persisted, acknowledged, and then dispatched")
  void validNotifyingEventIsStoredAcknowledgedAndDispatched() {
    ParcelEvent event = validEvent();
    UUID notificationId = UUID.randomUUID();
    when(parser.parse(TOPIC, PAYLOAD)).thenReturn(event);
    when(service.handle(event)).thenReturn(Optional.of(notificationId));

    consumer.onParcelEvent(record(PAYLOAD), ack);

    InOrder order = inOrder(service, ack, dispatcher);
    order.verify(service).handle(event);
    order.verify(ack).acknowledge();
    order.verify(dispatcher).deliver(notificationId);
  }

  @Test
  @DisplayName("Skipped or duplicate event is acknowledged without invoking the dispatcher")
  void skippedOrDuplicateEventIsAcknowledgedWithoutDispatch() {
    ParcelEvent event = validEvent();
    when(parser.parse(TOPIC, PAYLOAD)).thenReturn(event);
    when(service.handle(event)).thenReturn(Optional.empty());

    consumer.onParcelEvent(record(PAYLOAD), ack);

    verify(service).handle(event);
    verify(ack).acknowledge();
    verify(dispatcher, never()).deliver(any());
  }

  @Test
  @DisplayName("Unexpected error during first delivery attempt does not fail or un-acknowledge a stored event")
  void firstDeliveryExceptionIsCaughtAfterAcknowledge() {
    ParcelEvent event = validEvent();
    UUID notificationId = UUID.randomUUID();
    when(parser.parse(TOPIC, PAYLOAD)).thenReturn(event);
    when(service.handle(event)).thenReturn(Optional.of(notificationId));
    doThrow(new DataAccessResourceFailureException("db transient error"))
        .when(dispatcher).deliver(notificationId);

    assertThatCode(() -> consumer.onParcelEvent(record(PAYLOAD), ack))
        .doesNotThrowAnyException();

    verify(ack).acknowledge();
    verify(dispatcher).deliver(notificationId);
  }

  @Test
  @DisplayName("Malformed message is recorded as rejected and acknowledged without blocking the partition")
  void malformedMessageIsRejectedAndAcknowledged() {
    when(parser.parse(TOPIC, "bad-json")).thenThrow(new MalformedEventException("not valid JSON"));

    consumer.onParcelEvent(record("bad-json"), ack);

    verify(service).reject(TOPIC, 1, 42L, "not valid JSON");
    verify(ack).acknowledge();
    verify(service, never()).handle(any());
    verify(dispatcher, never()).deliver(any());
  }

  @Test
  @DisplayName("Malformed event is acknowledged and does not prevent the next valid event")
  void malformedEventDoesNotBlockNextValidEvent() {
    ParcelEvent event = validEvent();
    UUID notificationId = UUID.randomUUID();
    when(parser.parse(TOPIC, "bad-json")).thenThrow(new MalformedEventException("not valid JSON"));
    when(parser.parse(TOPIC, PAYLOAD)).thenReturn(event);
    when(service.handle(event)).thenReturn(Optional.of(notificationId));

    assertThatCode(() -> consumer.onParcelEvent(record("bad-json"), ack)).doesNotThrowAnyException();
    assertThatCode(() -> consumer.onParcelEvent(record(PAYLOAD), ack)).doesNotThrowAnyException();

    verify(service).reject(TOPIC, 1, 42L, "not valid JSON");
    verify(service).handle(event);
    verify(ack, org.mockito.Mockito.times(2)).acknowledge();
    verify(dispatcher).deliver(notificationId);
  }

  @Test
  @DisplayName("Database error while storing event is rethrown before acknowledging so Kafka redelivers")
  void storageFailureIsNotAcknowledged() {
    ParcelEvent event = validEvent();
    when(parser.parse(TOPIC, PAYLOAD)).thenReturn(event);
    when(service.handle(event)).thenThrow(new DataAccessResourceFailureException("db down"));

    assertThatThrownBy(() -> consumer.onParcelEvent(record(PAYLOAD), ack))
        .isInstanceOf(DataAccessResourceFailureException.class);

    verify(ack, never()).acknowledge();
    verify(dispatcher, never()).deliver(any());
  }
}

