package com.zentro.trackingreporting.messaging.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.reporting.DeliveryProjectionRepository;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
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
    onParcelEvent(message, topic, headerEventId, null);
  }

  @KafkaListener(topics = {"parcel.created", "parcel.status.updated"}, groupId = "${spring.kafka.consumer.group-id:tracking-reporting-service}")
  @Transactional
  public void onParcelEvent(@Payload String message,
      @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
      @Header(name = "eventId", required = false) String headerEventId,
      @Header(name = "schemaVersion", required = false) String schemaVersion) {
    if (schemaVersion != null && !"1".equals(schemaVersion)) {
      log.warn("Tracking event discarded: category=unsupported_version");
      return;
    }
    Event event;
    try {
      event = parse(message, topic);
    } catch (JsonProcessingException | IllegalArgumentException | java.time.DateTimeException ex) {
      log.warn("Tracking event discarded: category=invalid_contract");
      return;
    }
    // An INSERT ON CONFLICT waits for a concurrent claimant's commit or rollback.
    if (processed.claim("tracking-reporting-service", event.id()) == 0) return;
    projections.lock(event.parcel());
    tracking.recordEvent(event.ref(), event.parcel(), event.status(), event.previous(), event.actor(),
        event.status().defaultTitle(), event.reason() != null ? event.reason() : event.status().defaultDescription(),
        event.reason(), null, event.time(), event.id());
    processed.flush(); // history must be visible to the JDBC projection update in this same transaction
    projections.refresh(event.parcel());
  }

  private Event parse(String message, String topic) throws JsonProcessingException {
    if (message == null || message.length() > 65536) throw new IllegalArgumentException();
    JsonNode e = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(message);
    if (e == null || !e.isObject() || e.has("schemaVersion")) throw new IllegalArgumentException();
    allowed(e, java.util.Set.of("eventId", "eventType", "aggregateId", "occurredAt", "payload"));
    String type = text(e, "eventType", 100);
    if (!("parcel.created".equals(type) || "parcel.status.updated".equals(type)) || !type.equals(topic))
      throw new IllegalArgumentException();
    UUID id = uuid(e, "eventId");
    UUID parcel = uuid(e, "aggregateId");
    Instant time = Instant.parse(text(e, "occurredAt", 40));
    JsonNode p = e.path("payload");
    allowed(p, "parcel.created".equals(type)
        ? java.util.Set.of("trackingRef", "status", "source", "occurredAt", "merchantId")
        : java.util.Set.of("trackingRef", "status", "previousStatus", "actorRole", "occurredAt", "failureReason", "merchantId"));
    if (p.has("merchantId")) text(p, "merchantId", 255);
    String ref = text(p, "trackingRef", 13);
    if (!ref.matches("ZT-[0-9A-HJKMNP-TV-Z]{10}")) throw new IllegalArgumentException();
    TrackingStatus status = TrackingStatus.valueOf(text(p, "status", 32));
    Instant payloadTime = Instant.parse(text(p, "occurredAt", 40));
    if (!time.equals(payloadTime)) throw new IllegalArgumentException();
    String previous = null;
    String actor = "SYSTEM";
    if ("parcel.created".equals(type)) {
      String source = text(p, "source", 32);
      if (status != TrackingStatus.CREATED || !(source.equals("CUSTOMER_BOOKING") || source.equals("MERCHANT_ORDER")))
        throw new IllegalArgumentException();
    } else {
      previous = TrackingStatus.valueOf(text(p, "previousStatus", 32)).name();
      actor = text(p, "actorRole", 32);
      if (!actor.matches("[A-Z][A-Z_]*")) throw new IllegalArgumentException();
    }
    String reason = status == TrackingStatus.FAILED ? text(p, "failureReason", 200) : null;
    if (status != TrackingStatus.FAILED && p.has("failureReason")) throw new IllegalArgumentException();
    return new Event(id, parcel, ref, status, previous, actor, reason, time);
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

  private record Event(UUID id, UUID parcel, String ref, TrackingStatus status, String previous,
                       String actor, String reason, Instant time) {}
}
