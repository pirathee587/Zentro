package com.zentro.notificationintegration.consumer;

import com.zentro.notificationintegration.event.MalformedEventException;
import com.zentro.notificationintegration.event.ParcelEvent;
import com.zentro.notificationintegration.event.ParcelEventParser;
import com.zentro.notificationintegration.service.NotificationDispatcher;
import com.zentro.notificationintegration.service.NotificationService;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Consumes parcel.created and parcel.status.updated (at-least-once, ordered per parcel).
 *
 * <ul>
 *   <li>Valid event: stored in one transaction (duplicates are ignored), acknowledged after the commit, then the
 *       first delivery attempt is made. A failed attempt is left to the retry scheduler.</li>
 *   <li>Malformed message: recorded as a rejected event with a short reason and acknowledged, so it never blocks
 *       the partition and is never retried.</li>
 *   <li>Unexpected error while storing (for example the database is down): not acknowledged; the error handler
 *       redelivers the same message.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "zentro.notifications.consumer", name = "enabled", havingValue = "true")
public class ParcelEventConsumer {
  private static final Logger log = LoggerFactory.getLogger(ParcelEventConsumer.class);

  private final ParcelEventParser parser;
  private final NotificationService service;
  private final NotificationDispatcher dispatcher;

  public ParcelEventConsumer(ParcelEventParser parser, NotificationService service,
      NotificationDispatcher dispatcher) {
    this.parser = parser;
    this.service = service;
    this.dispatcher = dispatcher;
  }

  @KafkaListener(
      topics = {ParcelEventParser.PARCEL_CREATED, ParcelEventParser.PARCEL_STATUS_UPDATED},
      groupId = "${spring.kafka.consumer.group-id:notification-integration-service}")
  public void onParcelEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
    ParcelEvent event;
    try {
      event = parser.parse(record.topic(), record.value());
    } catch (MalformedEventException ex) {
      service.reject(record.topic(), record.partition(), record.offset(), ex.getMessage());
      ack.acknowledge();
      return;
    }

    Optional<UUID> toSend = service.handle(event); // throws on database errors: not acknowledged, redelivered
    ack.acknowledge();

    toSend.ifPresent(id -> {
      try {
        dispatcher.deliver(id);
      } catch (RuntimeException ex) {
        // The notification is stored as PENDING; the retry scheduler will send it.
        log.warn("First delivery attempt for notification {} postponed: {}", id, ex.getClass().getSimpleName());
      }
    });
  }
}
