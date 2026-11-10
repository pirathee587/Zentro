package com.zentro.notificationintegration.messaging.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parses and validates the parcel event envelope (contracts/events/*.schema.json). Anything that does not match
 * the agreed contract is rejected with a short reason; no ids are invented and no payload text is echoed.
 */
@Component
public class ParcelEventParser {
  public static final String PARCEL_CREATED = "parcel.created";
  public static final String PARCEL_STATUS_UPDATED = "parcel.status.updated";

  static final Set<String> STATUSES =
      Set.of("CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED");
  private static final Pattern TRACKING_REF = Pattern.compile("^ZT-[0-9A-HJKMNP-TV-Z]{10}$");
  private static final Map<String, String> TOPIC_EVENT_TYPES =
      Map.of(PARCEL_CREATED, PARCEL_CREATED, PARCEL_STATUS_UPDATED, PARCEL_STATUS_UPDATED);
  private static final int MAX_FAILURE_REASON = 200;

  private final ObjectMapper objectMapper;

  public ParcelEventParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** Event types accepted by the current notification consumer topics. */
  public static Set<String> supportedEventTypes() {
    return Set.copyOf(TOPIC_EVENT_TYPES.values());
  }

  public static void validateVersion(org.apache.kafka.common.header.Headers headers) {
    for (var header : headers.headers("schemaVersion")) {
      if (!java.util.Arrays.equals(header.value(), new byte[]{'1'}))
        throw new MalformedEventException("unsupported schema version");
    }
  }

  public ParcelEvent parse(String topic, String message) {
    if (message == null || message.isBlank()) {
      throw new MalformedEventException("empty message");
    }
    if (message.length() > 65536) throw new MalformedEventException("message too large");
    JsonNode envelope;
    try {
      envelope = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(message);
    } catch (JsonProcessingException ex) {
      throw new MalformedEventException("not valid JSON");
    }
    if (envelope == null || !envelope.isObject()) {
      throw new MalformedEventException("envelope is not a JSON object");
    }
    // The approved envelope is unversioned and has no schemaVersion property.
    if (envelope.has("schemaVersion")) throw new MalformedEventException("unsupported schema version");

    UUID eventId = uuid(envelope, "eventId");
    String eventType = text(envelope, "eventType");
    String expectedType = TOPIC_EVENT_TYPES.get(topic);
    if (expectedType == null) {
      throw new MalformedEventException("unexpected topic");
    }
    if (!expectedType.equals(eventType)) {
      throw new MalformedEventException("eventType does not match the topic");
    }
    UUID parcelId = uuid(envelope, "aggregateId");
    OffsetDateTime occurredAt = timestamp(envelope, "occurredAt");
    allowed(envelope, Set.of("eventId", "eventType", "aggregateId", "occurredAt", "payload"));

    JsonNode payload = envelope.get("payload");
    if (payload == null || !payload.isObject()) {
      throw new MalformedEventException("missing payload");
    }
    allowed(payload, PARCEL_CREATED.equals(eventType)
        ? Set.of("trackingRef", "status", "source", "occurredAt", "merchantId")
        : Set.of("trackingRef", "status", "previousStatus", "actorRole", "occurredAt", "failureReason", "merchantId"));
    if (!timestamp(payload, "occurredAt").toInstant().equals(occurredAt.toInstant()))
      throw new MalformedEventException("event timestamps disagree");
    if (PARCEL_CREATED.equals(eventType)) {
      if (!Set.of("CUSTOMER_BOOKING", "MERCHANT_ORDER").contains(text(payload, "source")))
        throw new MalformedEventException("invalid source");
    } else {
      if (!STATUSES.contains(text(payload, "previousStatus"))) throw new MalformedEventException("invalid previousStatus");
      String actor = text(payload, "actorRole");
      if (actor.length() > 32 || !actor.matches("[A-Z][A-Z_]*")) throw new MalformedEventException("invalid actorRole");
    }
    JsonNode merchant = payload.get("merchantId");
    if (merchant != null && (!merchant.isTextual() || merchant.textValue().isBlank() || merchant.textValue().length() > 255))
      throw new MalformedEventException("invalid merchantId");
    String trackingRef = text(payload, "trackingRef");
    if (!TRACKING_REF.matcher(trackingRef).matches()) {
      throw new MalformedEventException("invalid trackingRef");
    }
    String status = text(payload, "status");
    if (!STATUSES.contains(status)) {
      throw new MalformedEventException("unknown status");
    }
    if (PARCEL_CREATED.equals(eventType) && !"CREATED".equals(status)) {
      throw new MalformedEventException("parcel.created must have status CREATED");
    }

    JsonNode reasonNode = payload.get("failureReason");
    String failureReason = null;
    if ("FAILED".equals(status)) {
      if (reasonNode == null) {
        throw new MalformedEventException("FAILED without failureReason");
      }
      if (!reasonNode.isTextual()) {
        throw new MalformedEventException("invalid failureReason");
      }
      failureReason = reasonNode.asText();
      if (failureReason.isBlank()) {
        throw new MalformedEventException("FAILED without failureReason");
      }
      if (failureReason.codePointCount(0, failureReason.length()) > MAX_FAILURE_REASON) {
        throw new MalformedEventException("failureReason too long");
      }
    } else if (reasonNode != null) {
      throw new MalformedEventException("failureReason is only allowed for FAILED");
    }

    return new ParcelEvent(eventId, eventType, parcelId, occurredAt, trackingRef, status, failureReason);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new MalformedEventException("missing " + field);
    }
    return value.asText();
  }

  private static void allowed(JsonNode node, Set<String> fields) {
    var names = node.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next()))
      throw new MalformedEventException("unexpected event field");
  }

  private static UUID uuid(JsonNode node, String field) {
    String value = text(node, field);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ex) {
      throw new MalformedEventException("invalid " + field);
    }
  }

  private static OffsetDateTime timestamp(JsonNode node, String field) {
    String value = text(node, field);
    try {
      return OffsetDateTime.parse(value);
    } catch (DateTimeParseException ex) {
      throw new MalformedEventException("invalid " + field);
    }
  }
}
