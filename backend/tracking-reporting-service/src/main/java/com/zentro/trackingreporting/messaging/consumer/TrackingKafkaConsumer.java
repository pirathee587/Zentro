package com.zentro.trackingreporting.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
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

  public static final String PARCEL_CREATED = "parcel.created";
  public static final String PARCEL_STATUS_UPDATED = "parcel.status.updated";
  public static final String SUPPORTED_SCHEMA_VERSION = "1";

  private static final Pattern TRACKING_REF_PATTERN = Pattern.compile("^ZT-[0-9A-HJKMNP-TV-Z]{10}$");
  private static final Pattern ACTOR_ROLE_PATTERN = Pattern.compile("^[A-Z][A-Z_]*$");
  private static final Set<String> ALLOWED_SOURCES = Set.of("CUSTOMER_BOOKING", "MERCHANT_ORDER");
  private static final int MAX_FAILURE_REASON = 200;

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

  public void onParcelEvent(String message, String topic, String headerEventId) {
    onParcelEvent(message, topic, headerEventId, null);
  }

  @KafkaListener(topics = {"parcel.created", "parcel.status.updated"}, groupId = "${spring.kafka.consumer.group-id:tracking-reporting-service}")
  @Transactional
  public void onParcelEvent(
      @Payload String message,
      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
      @Header(name = "eventId", required = false) String headerEventId,
      @Header(name = "schemaVersion", required = false) Object headerSchemaVersion) {

    // 1. Schema version validation from record header (missing is accepted as legacy v1)
    String schemaVersion = extractHeaderString(headerSchemaVersion);
    if (schemaVersion != null && !SUPPORTED_SCHEMA_VERSION.equals(schemaVersion)) {
      log.warn("Tracking event discarded: category=unsupported_schema_version");
      return;
    }

    UUID eventId = null;
    try {
      // 2. Parse envelope JSON
      JsonNode envelope = objectMapper.readTree(message);
      if (envelope == null || !envelope.isObject()) {
        log.warn("Tracking event discarded: category=invalid_envelope");
        return;
      }

      // 3. Event ID validation & conflict detection
      String envelopeEventId = textOrNull(envelope, "eventId");
      String cleanHeaderEventId = (headerEventId != null && !headerEventId.isBlank()) ? headerEventId.trim() : null;

      if (envelopeEventId != null && cleanHeaderEventId != null && !envelopeEventId.equals(cleanHeaderEventId)) {
        log.warn("Tracking event discarded: category=conflicting_event_id");
        return;
      }

      String eventIdStr = envelopeEventId != null ? envelopeEventId : cleanHeaderEventId;
      if (eventIdStr == null) {
        log.warn("Tracking event discarded: category=missing_event_id");
        return;
      }

      try {
        eventId = UUID.fromString(eventIdStr);
      } catch (IllegalArgumentException ex) {
        log.warn("Tracking event discarded: category=invalid_event_id");
        return;
      }

      // De-duplicate on eventId
      if (processedEventRepository.existsByConsumerNameAndEventId(CONSUMER_NAME, eventId)) {
        log.debug("Skipping already processed event: {}", eventId);
        return;
      }

      // 4. Event type validation
      String eventType = textOrNull(envelope, "eventType");
      if (eventType == null) {
        log.warn("Tracking event discarded: category=missing_event_type eventId={}", eventId);
        return;
      }
      if (!PARCEL_CREATED.equals(eventType) && !PARCEL_STATUS_UPDATED.equals(eventType)) {
        log.warn("Tracking event discarded: category=unsupported_event_type eventId={}", eventId);
        return;
      }
      if (topic != null && !topic.isBlank() && !topic.equals(eventType)) {
        log.warn("Tracking event discarded: category=mismatched_event_type eventId={}", eventId);
        return;
      }

      // 5. Aggregate ID validation
      String aggregateIdStr = textOrNull(envelope, "aggregateId");
      if (aggregateIdStr == null) {
        log.warn("Tracking event discarded: category=missing_aggregate_id eventId={}", eventId);
        return;
      }
      UUID parcelId;
      try {
        parcelId = UUID.fromString(aggregateIdStr);
      } catch (IllegalArgumentException ex) {
        log.warn("Tracking event discarded: category=invalid_aggregate_id eventId={}", eventId);
        return;
      }

      // 6. OccurredAt timestamp validation
      String occurredAtStr = textOrNull(envelope, "occurredAt");
      if (occurredAtStr == null) {
        log.warn("Tracking event discarded: category=missing_occurred_at eventId={}", eventId);
        return;
      }
      Instant occurredAt;
      try {
        occurredAt = Instant.parse(occurredAtStr);
      } catch (Exception ex) {
        log.warn("Tracking event discarded: category=invalid_occurred_at eventId={}", eventId);
        return;
      }

      // 7. Payload validation
      JsonNode payload = envelope.get("payload");
      if (payload == null || !payload.isObject()) {
        log.warn("Tracking event discarded: category=missing_payload eventId={}", eventId);
        return;
      }

      // 8. Tracking reference validation
      String trackingRef = textOrNull(payload, "trackingRef");
      if (trackingRef == null) {
        log.warn("Tracking event discarded: category=missing_tracking_ref eventId={}", eventId);
        return;
      }
      if (!TRACKING_REF_PATTERN.matcher(trackingRef).matches()) {
        log.warn("Tracking event discarded: category=invalid_tracking_ref eventId={}", eventId);
        return;
      }

      // 9. Status validation
      String statusStr = textOrNull(payload, "status");
      if (statusStr == null) {
        log.warn("Tracking event discarded: category=missing_status eventId={}", eventId);
        return;
      }
      TrackingStatus status = TrackingStatus.fromString(statusStr);
      if (status == null) {
        log.warn("Tracking event discarded: category=invalid_status eventId={}", eventId);
        return;
      }
      if (PARCEL_CREATED.equals(eventType) && status != TrackingStatus.CREATED) {
        log.warn("Tracking event discarded: category=invalid_status eventId={}", eventId);
        return;
      }

      // 10. Source validation for parcel.created
      if (PARCEL_CREATED.equals(eventType)) {
        String source = textOrNull(payload, "source");
        if (source == null) {
          log.warn("Tracking event discarded: category=missing_source eventId={}", eventId);
          return;
        }
        if (!ALLOWED_SOURCES.contains(source)) {
          log.warn("Tracking event discarded: category=invalid_source eventId={}", eventId);
          return;
        }
      }

      // 11. PreviousStatus and ActorRole validation for parcel.status.updated
      String previousStatus = null;
      String actorRole = "SYSTEM";
      if (PARCEL_STATUS_UPDATED.equals(eventType)) {
        String prevStr = textOrNull(payload, "previousStatus");
        if (prevStr == null) {
          log.warn("Tracking event discarded: category=missing_previous_status eventId={}", eventId);
          return;
        }
        TrackingStatus prevStatus = TrackingStatus.fromString(prevStr);
        if (prevStatus == null) {
          log.warn("Tracking event discarded: category=invalid_previous_status eventId={}", eventId);
          return;
        }
        previousStatus = prevStatus.name();

        String roleStr = textOrNull(payload, "actorRole");
        if (roleStr == null) {
          log.warn("Tracking event discarded: category=missing_actor_role eventId={}", eventId);
          return;
        }
        if (!ACTOR_ROLE_PATTERN.matcher(roleStr).matches()) {
          log.warn("Tracking event discarded: category=invalid_actor_role eventId={}", eventId);
          return;
        }
        actorRole = roleStr;
      }

      // 12. Failure reason conditional validation
      String failureReason = null;
      JsonNode reasonNode = payload.get("failureReason");
      if (status == TrackingStatus.FAILED) {
        if (reasonNode == null || reasonNode.isNull() || !reasonNode.isTextual() || reasonNode.asText().isBlank()) {
          log.warn("Tracking event discarded: category=missing_failure_reason eventId={}", eventId);
          return;
        }
        failureReason = reasonNode.asText();
        if (failureReason.codePointCount(0, failureReason.length()) > MAX_FAILURE_REASON) {
          log.warn("Tracking event discarded: category=invalid_failure_reason eventId={}", eventId);
          return;
        }
      } else if (reasonNode != null && !reasonNode.isNull()) {
        log.warn("Tracking event discarded: category=unexpected_failure_reason eventId={}", eventId);
        return;
      }

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

  private static String extractHeaderString(Object header) {
    if (header == null) {
      return null;
    }
    if (header instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return header.toString();
  }

  private static String textOrNull(JsonNode node, String field) {
    if (node == null || !node.has(field)) {
      return null;
    }
    JsonNode val = node.get(field);
    if (val == null || val.isNull() || !val.isTextual()) {
      return null;
    }
    String s = val.asText();
    return s.isBlank() ? null : s;
  }
}

