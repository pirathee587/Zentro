package com.zentro.notificationintegration.messaging.consumer;

import com.zentro.notificationintegration.messaging.event.MalformedEventException;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import com.zentro.notificationintegration.webhook.config.WebhookConfig;
import com.zentro.notificationintegration.webhook.event.MerchantEventReader;
import com.zentro.notificationintegration.webhook.event.MerchantParcelEvent;
import com.zentro.notificationintegration.webhook.service.WebhookDeliveryService;
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
 * Consumes parcel.created and parcel.status.updated for merchant webhooks, in its own consumer group, separate
 * from notifications (at-least-once, ordered per parcel).
 *
 * <ul>
 *   <li>Valid event: stored in one transaction (duplicates and non-merchant parcels are skipped), acknowledged
 *       after the commit, then the first attempt is made. A failed attempt is left to the retry scheduler; the
 *       listener never sleeps.</li>
 *   <li>Malformed message: recorded as a rejected event with a short reason and acknowledged.</li>
 *   <li>Unexpected error while storing: not acknowledged; the error handler redelivers the same message.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "zentro.webhooks", name = "enabled", havingValue = "true")
public class MerchantWebhookConsumer {
  private static final Logger log = LoggerFactory.getLogger(MerchantWebhookConsumer.class);

  private final MerchantEventReader reader;
  private final WebhookDeliveryService service;

  public MerchantWebhookConsumer(MerchantEventReader reader, WebhookDeliveryService service) {
    this.reader = reader;
    this.service = service;
  }

  @KafkaListener(
      id = WebhookDeliveryService.CONSUMER,
      topics = {ParcelEventParser.PARCEL_CREATED, ParcelEventParser.PARCEL_STATUS_UPDATED},
      groupId = "${zentro.webhooks.consumer-group:merchant-webhooks}",
      containerFactory = WebhookConfig.LISTENER_CONTAINER_FACTORY)
  public void onParcelEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
    MerchantParcelEvent event;
    try {
      event = reader.read(record.topic(), record.value());
    } catch (MalformedEventException ex) {
      service.reject(record.topic(), record.partition(), record.offset(), ex.getMessage());
      ack.acknowledge();
      return;
    }

    Optional<UUID> toSend = service.handle(event); // throws on database errors: not acknowledged, redelivered
    ack.acknowledge();

    toSend.ifPresent(id -> {
      try {
        service.attempt(id);
      } catch (RuntimeException ex) {
        // The delivery is stored as PENDING; the retry scheduler will send it.
        log.warn("First webhook attempt for delivery {} postponed: {}", id, ex.getClass().getSimpleName());
      }
    });
  }
}
