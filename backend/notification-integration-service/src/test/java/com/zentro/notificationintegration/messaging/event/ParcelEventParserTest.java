package com.zentro.notificationintegration.messaging.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParcelEventParserTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String EVENT_ID = "6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10";
  private static final String PARCEL_ID = "3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c";

  private final ParcelEventParser parser = new ParcelEventParser(MAPPER);

  @Test void explicitUnknownVersionAndOversizeAreRejectedWithoutEchoingValues() {
    var event = createdEvent().put("schemaVersion", "synthetic-private-value");
    assertThatThrownBy(() -> parser.parse("parcel.created", event.toString()))
        .isInstanceOf(MalformedEventException.class).hasMessage("unsupported schema version");
    assertThatThrownBy(() -> parser.parse("parcel.created", "x".repeat(65537)))
        .isInstanceOf(MalformedEventException.class).hasMessage("message too large");
  }

  @Test void fieldsOutsideApprovedSchemaAndMissingRequiredFieldsAreRejected() {
    for (String message : new String[]{createdEvent().toString() + " {}",
        createdEvent().toString().replace("\"eventId\":", "\"eventId\":\"duplicate-value\",\"eventId\":")})
      assertThatThrownBy(() -> parser.parse("parcel.created", message)).isInstanceOf(MalformedEventException.class);
    for (String field : new String[]{"source", "occurredAt"}) {
      var event = createdEvent(); payload(event).remove(field);
      assertThatThrownBy(() -> parser.parse("parcel.created", event.toString())).isInstanceOf(MalformedEventException.class);
    }
    var event = createdEvent(); payload(event).put("syntheticPrivateField", "must-not-store");
    assertThatThrownBy(() -> parser.parse("parcel.created", event.toString()))
        .isInstanceOf(MalformedEventException.class).hasMessage("unexpected event field");
  }

  static ObjectNode statusEvent(String status, String failureReason) {
    ObjectNode envelope = MAPPER.createObjectNode()
        .put("eventId", EVENT_ID)
        .put("eventType", "parcel.status.updated")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z")
        .put("aggregateId", PARCEL_ID);
    ObjectNode payload = envelope.putObject("payload")
        .put("trackingRef", "ZT-ABCDEFGH12")
        .put("previousStatus", "PICKED_UP")
        .put("status", status)
        .put("actorRole", "DISPATCHER")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z");
    if (failureReason != null) {
      payload.put("failureReason", failureReason);
    }
    return envelope;
  }

  static ObjectNode createdEvent() {
    ObjectNode envelope = MAPPER.createObjectNode()
        .put("eventId", EVENT_ID)
        .put("eventType", "parcel.created")
        .put("occurredAt", "2026-11-05T10:15:00Z")
        .put("aggregateId", PARCEL_ID);
    envelope.putObject("payload")
        .put("trackingRef", "ZT-ABCDEFGH12")
        .put("status", "CREATED")
        .put("source", "CUSTOMER_BOOKING")
        .put("occurredAt", "2026-11-05T10:15:00Z");
    return envelope;
  }

  private void assertMalformed(String topic, ObjectNode envelope, Consumer<ObjectNode> change, String reason) {
    change.accept(envelope);
    assertThatThrownBy(() -> parser.parse(topic, envelope.toString()))
        .isInstanceOf(MalformedEventException.class)
        .hasMessageContaining(reason);
  }

  private static ObjectNode payload(ObjectNode envelope) {
    return (ObjectNode) envelope.get("payload");
  }

  @Test
  @DisplayName("A valid parcel.status.updated event is parsed")
  void validStatusEvent() {
    ParcelEvent event = parser.parse("parcel.status.updated", statusEvent("PICKED_UP", null).toString());

    assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
    assertThat(event.eventType()).isEqualTo("parcel.status.updated");
    assertThat(event.parcelId()).isEqualTo(UUID.fromString(PARCEL_ID));
    assertThat(event.trackingRef()).isEqualTo("ZT-ABCDEFGH12");
    assertThat(event.status()).isEqualTo("PICKED_UP");
    assertThat(event.failureReason()).isNull();
    assertThat(event.occurredAt()).isNotNull();
  }

  @Test
  @DisplayName("A valid FAILED event keeps its failure reason")
  void validFailedEvent() {
    ParcelEvent event = parser.parse("parcel.status.updated",
        statusEvent("FAILED", "Recipient not available").toString());

    assertThat(event.status()).isEqualTo("FAILED");
    assertThat(event.failureReason()).isEqualTo("Recipient not available");
  }

  @Test
  @DisplayName("A valid parcel.created event is parsed")
  void validCreatedEvent() {
    ParcelEvent event = parser.parse("parcel.created", createdEvent().toString());

    assertThat(event.eventType()).isEqualTo("parcel.created");
    assertThat(event.status()).isEqualTo("CREATED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "   ", "not json", "{\"bad\":", "[1,2]", "\"text\""})
  @DisplayName("Empty, non-JSON and non-object messages are malformed")
  void unreadableMessages(String message) {
    assertThatThrownBy(() -> parser.parse("parcel.status.updated", message))
        .isInstanceOf(MalformedEventException.class);
  }

  @Test
  @DisplayName("{\"bad\":\"json\"} is malformed (missing eventId)")
  void unrelatedJson() {
    assertThatThrownBy(() -> parser.parse("parcel.status.updated", "{\"bad\":\"json\"}"))
        .isInstanceOf(MalformedEventException.class).hasMessage("missing eventId");
  }

  @Test
  @DisplayName("Each missing or invalid field is reported with a short reason")
  void invalidFields() {
    String t = "parcel.status.updated";
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> e.remove("eventId"), "missing eventId");
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> e.put("eventId", "123"), "invalid eventId");
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> e.remove("eventType"), "missing eventType");
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> e.put("aggregateId", "parcel-1"), "invalid aggregateId");
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> e.put("occurredAt", "yesterday"), "invalid occurredAt");
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> e.remove("payload"), "missing payload");
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> payload(e).put("trackingRef", "ZT-123"),
        "invalid trackingRef");
    assertMalformed(t, statusEvent("PICKED_UP", null), e -> payload(e).remove("trackingRef"), "missing trackingRef");
    assertMalformed(t, statusEvent("LOST", null), e -> { }, "unknown status");
  }

  @Test
  @DisplayName("The event type must match the topic")
  void eventTypeMustMatchTopic() {
    assertThatThrownBy(() -> parser.parse("parcel.created", statusEvent("PICKED_UP", null).toString()))
        .isInstanceOf(MalformedEventException.class).hasMessage("eventType does not match the topic");
    assertThatThrownBy(() -> parser.parse("other.topic", statusEvent("PICKED_UP", null).toString()))
        .isInstanceOf(MalformedEventException.class).hasMessage("unexpected topic");
  }

  @Test
  @DisplayName("FAILED needs a reason, and a reason is only allowed for FAILED")
  void failureReasonRules() {
    String t = "parcel.status.updated";
    assertMalformed(t, statusEvent("FAILED", null), e -> { }, "FAILED without failureReason");
    assertMalformed(t, statusEvent("FAILED", "  "), e -> { }, "FAILED without failureReason");
    assertMalformed(t, statusEvent("FAILED", "x".repeat(201)), e -> { }, "failureReason too long");
    assertMalformed(t, statusEvent("FAILED", null), e -> payload(e).put("failureReason", 123),
        "invalid failureReason");
    assertMalformed(t, statusEvent("DELIVERED", null), e -> payload(e).putNull("failureReason"),
        "failureReason is only allowed for FAILED");
    assertMalformed(t, statusEvent("DELIVERED", "Lost"), e -> { }, "failureReason is only allowed for FAILED");
  }

  @Test
  @DisplayName("parcel.created must carry status CREATED")
  void createdMustBeCreated() {
    assertMalformed("parcel.created", createdEvent(), e -> payload(e).put("status", "PICKED_UP"),
        "parcel.created must have status CREATED");
  }

  @Test
  @DisplayName("Reasons never echo the message content")
  void reasonsDoNotEchoContent() {
    ObjectNode envelope = statusEvent("PICKED_UP", null);
    payload(envelope).put("trackingRef", "secret-0771234567");
    assertThatThrownBy(() -> parser.parse("parcel.status.updated", envelope.toString()))
        .isInstanceOf(MalformedEventException.class)
        .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("0771234567"));
  }
}
