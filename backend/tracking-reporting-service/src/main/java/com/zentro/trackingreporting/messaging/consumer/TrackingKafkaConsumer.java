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

    UUID eventId = null;
    try {
      JsonNode envelope = objectMapper.readTree(message);
      if (envelope == null || !envelope.isObject()) {
        log.warn("Tracking event discarded: category=invalid_envelope");
        return;
      }

      String eventIdStr = envelope.has("eventId") ? envelope.get("eventId").asText() : headerEventId;
      try {
        eventId = (eventIdStr != null && !eventIdStr.isBlank()) ? UUID.fromString(eventIdStr) : UUID.randomUUID();
      } catch (IllegalArgumentException ex) {
        log.warn("Tracking event discarded: category=invalid_event_id");
        return;
      }

      // De-duplicate on eventId
      if (processedEventRepository.existsByConsumerNameAndEventId(CONSUMER_NAME, eventId)) {
        log.debug("Skipping already processed event: {}", eventId);
        return;
      }

      String eventType = envelope.has("eventType") ? envelope.get("eventType").asText() : topic;
      String aggregateIdStr = envelope.has("aggregateId") ? envelope.get("aggregateId").asText() : null;
      UUID parcelId = null;
      if (aggregateIdStr != null && !aggregateIdStr.isBlank()) {
        try {
          parcelId = UUID.fromString(aggregateIdStr);
        } catch (IllegalArgumentException ex) {
          log.warn("Tracking event fallback: category=invalid_aggregate_id eventId={}", eventId);
        }
      }
      Instant occurredAt = Instant.now();
      if (envelope.has("occurredAt")) {
        try {
          occurredAt = Instant.parse(envelope.get("occurredAt").asText());
        } catch (Exception ex) {
          log.warn("Tracking event fallback: category=invalid_occurred_at eventId={}", eventId);
        }
      }

      JsonNode payload = envelope.get("payload");
      if (payload == null) {
        log.warn("Tracking event discarded: category=missing_payload eventId={}", eventId);
        return;
      }

      String trackingRef = payload.has("trackingRef") ? payload.get("trackingRef").asText() : null;
      if (trackingRef == null || trackingRef.isBlank()) {
        log.warn("Tracking event discarded: category=missing_tracking_ref eventId={}", eventId);
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
      log.info("Tracking event processed: eventId={}", eventId);

    } catch (Exception e) {
      // Payloads and exception chains can contain secrets. Preserve the cause for retry classification only.
      log.error("Tracking event failed: category=processing_error eventId={}", eventId);
      throw new RuntimeException("Error processing Kafka tracking event", e);
    }
  }
}

