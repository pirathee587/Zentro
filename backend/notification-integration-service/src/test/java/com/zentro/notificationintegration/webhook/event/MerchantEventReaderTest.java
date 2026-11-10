package com.zentro.notificationintegration.webhook.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zentro.notificationintegration.messaging.event.MalformedEventException;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MerchantEventReaderTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final MerchantEventReader reader = new MerchantEventReader(new ParcelEventParser(mapper), mapper);
  private final UUID eventId = UUID.randomUUID();
  private final UUID parcelId = UUID.randomUUID();

  private ObjectNode statusEvent() {
    ObjectNode envelope = mapper.createObjectNode()
        .put("eventId", eventId.toString())
        .put("eventType", "parcel.status.updated")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z")
        .put("aggregateId", parcelId.toString());
    envelope.putObject("payload")
        .put("trackingRef", "ZT-ABCDEFGH12")
        .put("previousStatus", "CREATED")
        .put("status", "PICKED_UP")
        .put("actorRole", "DISPATCHER")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z");
    return envelope;
  }

  @Test
  @DisplayName("A merchant event keeps its merchantId and the raw envelope unchanged")
  void merchantEvent() {
    ObjectNode envelope = statusEvent();
    ((ObjectNode) envelope.get("payload")).put("merchantId", "merchant-123");
    String raw = envelope.toString();

    MerchantParcelEvent event = reader.read("parcel.status.updated", raw);

    assertThat(event.merchantId()).isEqualTo("merchant-123");
    assertThat(event.rawEnvelope()).isSameAs(raw);
    assertThat(event.event().eventId()).isEqualTo(eventId);
    assertThat(event.event().parcelId()).isEqualTo(parcelId);
    assertThat(event.toString()).doesNotContain("ZT-ABCDEFGH12");
  }

  @Test
  @DisplayName("An absent merchantId is a customer event; explicit null violates the approved string schema")
  void customerEvent() {
    assertThat(reader.read("parcel.status.updated", statusEvent().toString()).merchantId()).isNull();
    ObjectNode withNull = statusEvent();
    ((ObjectNode) withNull.get("payload")).putNull("merchantId");
    assertThatThrownBy(() -> reader.read("parcel.status.updated", withNull.toString()))
        .isInstanceOf(MalformedEventException.class).hasMessage("invalid merchantId");
  }

  @Test
  @DisplayName("An empty, non-string or too long merchantId is malformed")
  void invalidMerchantId() {
    ObjectNode empty = statusEvent();
    ((ObjectNode) empty.get("payload")).put("merchantId", " ");
    ObjectNode number = statusEvent();
    ((ObjectNode) number.get("payload")).put("merchantId", 7);
    ObjectNode tooLong = statusEvent();
    ((ObjectNode) tooLong.get("payload")).put("merchantId", "m".repeat(256));

    for (ObjectNode envelope : new ObjectNode[] {empty, number, tooLong}) {
      assertThatThrownBy(() -> reader.read("parcel.status.updated", envelope.toString()))
          .isInstanceOf(MalformedEventException.class).hasMessage("invalid merchantId");
    }
  }

  @Test
  @DisplayName("Envelope rules are the shared parser's rules (wrong topic, bad JSON)")
  void envelopeRulesAreShared() {
    assertThatThrownBy(() -> reader.read("parcel.created", statusEvent().toString()))
        .isInstanceOf(MalformedEventException.class);
    assertThatThrownBy(() -> reader.read("parcel.status.updated", "{not json"))
        .isInstanceOf(MalformedEventException.class);
  }
}
