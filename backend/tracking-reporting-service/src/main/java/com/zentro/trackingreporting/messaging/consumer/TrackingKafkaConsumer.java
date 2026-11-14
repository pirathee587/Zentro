package com.zentro.trackingreporting.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.reporting.DeliveryProjectionRepository;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.nio.charset.StandardCharsets;
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
  private final TrackingService tracking;
  private final ProcessedEventRepository processed;
  private final ObjectMapper mapper;
  private final DeliveryProjectionRepository projections;

  public TrackingKafkaConsumer(TrackingService tracking, ProcessedEventRepository processed, ObjectMapper mapper,
      DeliveryProjectionRepository projections) {
    this.tracking = tracking;
    this.processed = processed;
    this.mapper = mapper;
    this.projections = projections;
  }

  @Transactional
  public void onParcelEvent(String message, String topic, String headerEventId) {
    onParcelEvent(message, topic, headerEventId, (String) null);
  }

  @Transactional
  public void onParcelEvent(String message, String topic, String headerEventId, Object schemaVersionHeader) {
    onParcelEvent(message, topic, headerEventId, extractHeaderString(schemaVersionHeader));
  }

  @Transactional
  public void onAssignmentEvent(String message, String topic, String headerEventId) {
    onAssignmentEvent(message, topic, headerEventId, (String) null);
  }

  @Transactional
  public void onAssignmentEvent(String message, String topic, String headerEventId, Object schemaVersionHeader) {
    onAssignmentEvent(message, topic, headerEventId, extractHeaderString(schemaVersionHeader));
  }

  @KafkaListener(topics = {"delivery.assigned", "delivery.reassigned", "delivery.cancelled"}, groupId = "${spring.kafka.consumer.group-id:tracking-reporting-service}")
  @Transactional
  public void onAssignmentEvent(@Payload String message,
      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
      @Header(name = "eventId", required = false) String headerEventId,
      @Header(name = "schemaVersion", required = false) String schemaVersion) {
    if (schemaVersion != null && !"1".equals(schemaVersion)) {
      log.warn("Assignment event discarded: category=unsupported_schema_version");
      return;
    }
    if (message == null || message.isBlank()) return;
    try {
      JsonNode node = mapper.readTree(message);
      if (node == null || !node.isObject()) return;

      UUID eventId = node.has("eventId") && !node.get("eventId").isNull()
          ? UUID.fromString(node.get("eventId").asText())
          : UUID.randomUUID();

      if (headerEventId != null && !headerEventId.isBlank()) {
        try {
          UUID hId = UUID.fromString(headerEventId.trim());
          if (!hId.equals(eventId)) {
            log.warn("Assignment event discarded: category=conflicting_event_id");
            return;
          }
        } catch (IllegalArgumentException ex) {
          log.warn("Assignment event discarded: category=invalid_header_event_id");
          return;
        }
      }

      if (processed != null && processed.claim("tracking-reporting-service", eventId) == 0) return;

      UUID parcelId = node.has("aggregateId") && !node.get("aggregateId").isNull()
          ? UUID.fromString(node.get("aggregateId").asText())
          : null;

      JsonNode payload = node.path("payload");
      String action = payload.has("action") && !payload.get("action").isNull()
          ? payload.get("action").asText()
          : null;
      if (action == null && topic != null) {
        if (topic.endsWith(".assigned")) action = "ASSIGNED";
        else if (topic.endsWith(".reassigned")) action = "REASSIGNED";
        else if (topic.endsWith(".cancelled")) action = "CANCELLED";
      }

      String newCourierId = payload.has("newCourierId") && !payload.get("newCourierId").isNull()
          ? payload.get("newCourierId").asText()
          : null;

      tracking.updateCourierAssignment(parcelId, null, newCourierId, action != null ? action : "ASSIGNED");

      if (processed != null) {
        processed.flush();
      }
    } catch (Exception ex) {
      log.warn("Assignment event discarded: error={}", ex.getMessage());
    }
  }

  @KafkaListener(topics = {"parcel.created", "parcel.status.updated"}, groupId = "${spring.kafka.consumer.group-id:tracking-reporting-service}")
  @Transactional
  public void onParcelEvent(@Payload String message,
      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
      @Header(name = "eventId", required = false) String headerEventId,
      @Header(name = "schemaVersion", required = false) String schemaVersion) {
    if (schemaVersion != null && !"1".equals(schemaVersion)) {
      log.warn("Tracking event discarded: category=unsupported_version category=unsupported_schema_version");
      return;
    }
    Event event;
    try {
      event = parse(message, topic);
    } catch (ContractException ex) {
      log.warn("Tracking event discarded: category=invalid_contract category={}", ex.category);
      return;
    } catch (JsonProcessingException | IllegalArgumentException | java.time.DateTimeException ex) {
      log.warn("Tracking event discarded: category=invalid_contract");
      return;
    }
    if (headerEventId != null && !headerEventId.isBlank()) {
      try {
        UUID hId = UUID.fromString(headerEventId.trim());
        if (!hId.equals(event.id())) {
          log.warn("Tracking event discarded: category=invalid_contract category=conflicting_event_id");
          return;
        }
      } catch (IllegalArgumentException ex) {
        log.warn("Tracking event discarded: category=invalid_contract category=invalid_header_event_id category=invalid_event_id");
        return;
      }
    }
    // An INSERT ON CONFLICT waits for a concurrent claimant's commit or rollback.
    if (processed != null && processed.claim("tracking-reporting-service", event.id()) == 0) return;
    if (projections != null) {
      projections.lock(event.parcel());
    }
    tracking.recordEvent(event.ref(), event.parcel(), event.status(), event.previous(), event.actor(),
        event.status().defaultTitle(), event.reason() != null ? event.reason() : event.status().defaultDescription(),
        event.reason(), null, event.time(), event.id());
    if (processed != null) {
      processed.flush(); // history must be visible to the JDBC projection update in this same transaction
    }
    if (projections != null) {
      projections.refresh(event.parcel());
    }
  }

  private Event parse(String message, String topic) throws JsonProcessingException {
    if (message == null || message.length() > 65536) throw new ContractException("invalid_contract");
    JsonNode e;
    try {
      e = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(message);
    } catch (JsonProcessingException ex) {
      throw ex;
    }
    if (e == null || !e.isObject()) throw new ContractException("invalid_envelope");
    if (e.has("schemaVersion")) throw new ContractException("unsupported_schema_version");
    allowed(e, java.util.Set.of("eventId", "eventType", "aggregateId", "occurredAt", "payload"));

    if (!e.has("eventId") || e.get("eventId").isNull()) throw new ContractException("missing_event_id");
    UUID id;
    try {
      id = uuid(e, "eventId");
    } catch (Exception ex) {
      throw new ContractException("invalid_event_id");
    }

    if (!e.has("eventType") || e.get("eventType").isNull()) throw new ContractException("missing_event_type");
    String type = text(e, "eventType", 100);
    if (!("parcel.created".equals(type) || "parcel.status.updated".equals(type)) || !type.equals(topic)) {
      throw new ContractException("mismatched_event_type");
    }

    if (!e.has("aggregateId") || e.get("aggregateId").isNull()) throw new ContractException("missing_aggregate_id");
    UUID parcel;
    try {
      parcel = uuid(e, "aggregateId");
    } catch (Exception ex) {
      throw new ContractException("invalid_aggregate_id");
    }

    if (!e.has("occurredAt") || e.get("occurredAt").isNull()) throw new ContractException("missing_occurred_at");
    Instant time;
    try {
      time = Instant.parse(text(e, "occurredAt", 40));
    } catch (Exception ex) {
      throw new ContractException("invalid_occurred_at");
    }

    JsonNode p = e.path("payload");
    if (p == null || !p.isObject()) throw new ContractException("missing_payload");
    allowed(p, "parcel.created".equals(type)
        ? java.util.Set.of("trackingRef", "status", "source", "occurredAt", "merchantId")
        : java.util.Set.of("trackingRef", "status", "previousStatus", "actorRole", "occurredAt", "failureReason", "merchantId"));
    if (p.has("merchantId")) text(p, "merchantId", 255);

    if (!p.has("trackingRef") || p.get("trackingRef").isNull() || !p.get("trackingRef").isTextual()) {
      throw new ContractException("missing_tracking_ref");
    }
    String ref = p.get("trackingRef").asText();
    if (!ref.matches("ZT-[0-9A-HJKMNP-TV-Z]{10}")) {
      throw new ContractException("invalid_tracking_ref");
    }

    if (!p.has("status") || p.get("status").isNull()) throw new ContractException("missing_status");
    TrackingStatus status;
    try {
      status = TrackingStatus.valueOf(text(p, "status", 32));
    } catch (Exception ex) {
      throw new ContractException("invalid_status");
    }

    if (p.has("occurredAt") && !p.get("occurredAt").isNull()) {
      Instant payloadTime;
      try {
        payloadTime = Instant.parse(text(p, "occurredAt", 40));
      } catch (Exception ex) {
        throw new ContractException("invalid_occurred_at");
      }
      if (!time.equals(payloadTime)) throw new ContractException("invalid_contract");
    }

    String previous = null;
    String actor = "SYSTEM";
    if ("parcel.created".equals(type)) {
      if (status != TrackingStatus.CREATED) throw new ContractException("invalid_status");
      if (!p.has("source") || p.get("source").isNull()) throw new ContractException("missing_source");
      String source = text(p, "source", 32);
      if (!(source.equals("CUSTOMER_BOOKING") || source.equals("MERCHANT_ORDER"))) {
        throw new ContractException("invalid_source");
      }
    } else {
      if (p.has("previousStatus") && !p.get("previousStatus").isNull()) {
        try {
          previous = TrackingStatus.valueOf(text(p, "previousStatus", 32)).name();
        } catch (Exception ex) {
          throw new ContractException("invalid_status");
        }
      }
      if (p.has("actorRole") && !p.get("actorRole").isNull()) {
        actor = text(p, "actorRole", 32);
        if (!actor.matches("[A-Z][A-Z_]*")) throw new ContractException("invalid_actor_role");
      }
    }
    String reason = null;
    if (status == TrackingStatus.FAILED) {
      if (!p.has("failureReason") || p.get("failureReason").isNull() || p.get("failureReason").asText().isBlank()) {
        throw new ContractException("missing_failure_reason");
      }
      reason = text(p, "failureReason", 200);
    } else if (p.has("failureReason") && !p.get("failureReason").isNull()) {
      throw new ContractException("unexpected_failure_reason");
    }
    return new Event(id, parcel, ref, status, previous, actor, reason, time);
  }

  private static String extractHeaderString(Object header) {
    if (header == null) {
      return null;
    }
    if (header instanceof byte[] bytes) {
      return new String(bytes, StandardCharsets.UTF_8);
    }
    if ("org.springframework.kafka.support.KafkaNull".equals(header.getClass().getName())) {
      return null;
    }
    return header.toString();
  }

  private String text(JsonNode node, String key, int max) {
    JsonNode value = node.get(key);
    if (value == null || !value.isTextual() || value.textValue().isBlank() || value.textValue().length() > max)
      throw new IllegalArgumentException();
    return value.textValue();
  }

  private UUID uuid(JsonNode node, String key) {
    String value = text(node, key, 36);
    UUID uuid = UUID.fromString(value);
    if (!uuid.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
    return uuid;
  }

  private void allowed(JsonNode node, java.util.Set<String> fields) {
    var names = node.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next())) throw new IllegalArgumentException();
  }

  static class ContractException extends IllegalArgumentException {
    final String category;
    ContractException(String category) {
      super(category);
      this.category = category;
    }
  }

  private record Event(UUID id, UUID parcel, String ref, TrackingStatus status, String previous,
                       String actor, String reason, Instant time) {}
}
