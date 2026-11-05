package com.zentro.trackingreporting.messaging.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(prefix = "zentro.kafka", name = "enabled", havingValue = "true", matchIfMissing = false)
public class TrackingKafkaConsumer {

  private static final Logger log = LoggerFactory.getLogger(TrackingKafkaConsumer.class);
  private static final String CONSUMER_NAME = "tracking-reporting-service";

  private final TrackingService trackingService;
  private final ProcessedEventRepository processedEventRepository;
  private final ObjectMapper objectMapper;

  public TrackingKafkaConsumer(
      TrackingService trackingService,
      ProcessedEventRepository processedEventRepository,
      ObjectMapper objectMapper) {
    this.trackingService = trackingService;
    this.processedEventRepository = processedEventRepository;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = {"parcel.created", "parcel.status.updated"}, groupId = "${spring.kafka.consumer.group-id:tracking-reporting-service}")
  @Transactional
  public void onParcelEvent(
      @Payload String message,
      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
      @Header(name = "eventId", required = false) String headerEventId) {

    try {
      JsonNode envelope = objectMapper.readTree(message);
      String eventIdStr = envelope.has("eventId") ? envelope.get("eventId").asText() : headerEventId;
      UUID eventId = (eventIdStr != null && !eventIdStr.isBlank()) ? UUID.fromString(eventIdStr) : UUID.randomUUID();

      // De-duplicate on eventId
      if (processedEventRepository.existsByConsumerNameAndEventId(CONSUMER_NAME, eventId)) {
        log.debug("Skipping already processed event: {}", eventId);
        return;
      }

      String eventType = envelope.has("eventType") ? envelope.get("eventType").asText() : topic;
      String aggregateIdStr = envelope.has("aggregateId") ? envelope.get("aggregateId").asText() : null;
      UUID parcelId = (aggregateIdStr != null && !aggregateIdStr.isBlank()) ? UUID.fromString(aggregateIdStr) : null;
      Instant occurredAt = envelope.has("occurredAt")
          ? Instant.parse(envelope.get("occurredAt").asText())
          : Instant.now();

      JsonNode payload = envelope.get("payload");
      if (payload == null) {
        log.warn("Discarding malformed envelope without payload: {}", message);
        return;
      }

      String trackingRef = payload.has("trackingRef") ? payload.get("trackingRef").asText() : null;
      if (trackingRef == null || trackingRef.isBlank()) {
        log.warn("Discarding event without trackingRef: {}", message);
        return;
      }

      String statusStr = payload.has("status") ? payload.get("status").asText() : "CREATED";
      TrackingStatus status = TrackingStatus.fromString(statusStr);
      String previousStatus = payload.has("previousStatus") ? payload.get("previousStatus").asText() : null;
      String actorRole = payload.has("actorRole") ? payload.get("actorRole").asText() : "SYSTEM";
      String failureReason = payload.has("failureReason") ? payload.get("failureReason").asText() : null;

      trackingService.recordEvent(
          trackingRef,
          parcelId,
          status,
          previousStatus,
          actorRole,
          status.defaultTitle(),
          failureReason != null ? failureReason : status.defaultDescription(),
          failureReason,
          null,
          occurredAt,
          eventId
      );

      processedEventRepository.save(new ProcessedEvent(CONSUMER_NAME, eventId));
      log.info("Successfully processed tracking event {} for {}", eventId, trackingRef);

    } catch (Exception e) {
      log.error("Failed to process incoming parcel event: {}", message, e);
      throw new RuntimeException("Error processing Kafka tracking event", e);
    }
  }
}

