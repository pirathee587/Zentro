package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Consumer enabled, mock sender working: success, duplicates, skipped statuses, malformed messages, security. */
class NotificationKafkaIntegrationTest extends AbstractNotificationIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired MeterRegistry registry;

  @Test
  @DisplayName("A valid parcel.status.updated event produces one SENT notification")
  void validStatusEventIsSent() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String ref = newTrackingRef();

    send(STATUS_TOPIC, parcelId, statusEvent(eventId, parcelId, ref, "PICKED_UP", null));

    Map<String, Object> row = awaitRow(eventId, "SENT");
    assertThat(row.get("tracking_ref")).isEqualTo(ref);
    assertThat(row.get("parcel_status")).isEqualTo("PICKED_UP");
    assertThat(row.get("message")).isEqualTo("Your parcel " + ref + " has been picked up.");
    assertThat(row.get("attempts")).isEqualTo(1);
    assertThat(row.get("last_error")).isNull();
  }

  @Test
  @DisplayName("parcel.created and FAILED events use their messages; FAILED includes the reason")
  void createdAndFailedMessages() throws Exception {
    UUID created = UUID.randomUUID();
    UUID failed = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String ref = newTrackingRef();

    send(CREATED_TOPIC, parcelId, createdEvent(created, parcelId, ref));
    send(STATUS_TOPIC, parcelId, statusEvent(failed, parcelId, ref, "FAILED", "Recipient not available"));

    assertThat(awaitRow(created, "SENT").get("message")).isEqualTo("Your SwiftDrop parcel " + ref + " has been booked.");
    assertThat(awaitRow(failed, "SENT").get("message"))
        .isEqualTo("Delivery of parcel " + ref
            + " was not completed: Recipient not available. Please contact support for help.");
  }

  @Test
  @DisplayName("IN_TRANSIT is recorded as SKIPPED")
  void inTransitIsSkipped() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(eventId, parcelId, newTrackingRef(), "IN_TRANSIT", null));

    Map<String, Object> row = awaitRow(eventId, "SKIPPED");
    assertThat(row.get("attempts")).isEqualTo(0);
  }

  @Test
  @DisplayName("The same event delivered twice produces one notification and counts a duplicate")
  void duplicateIsIgnored() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String event = statusEvent(eventId, parcelId, newTrackingRef(), "DELIVERED", null);
    double before = registry.counter("zentro.notifications.duplicates").count();

    send(STATUS_TOPIC, parcelId, event);
    awaitRow(eventId, "SENT");
    send(STATUS_TOPIC, parcelId, event);

    await().atMost(Duration.ofSeconds(30))
        .until(() -> registry.counter("zentro.notifications.duplicates").count() > before);
    assertThat(rowsFor(eventId)).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification.processed_event WHERE event_id = ?",
        Integer.class, eventId)).isEqualTo(1);
  }

  @Test
  @DisplayName("Event replay across multiple rounds preserves single-row idempotency and produces no duplicate attempts")
  void eventReplayPreservesIdempotency() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String ref = newTrackingRef();
    String event = statusEvent(eventId, parcelId, ref, "DELIVERED", null);

    send(STATUS_TOPIC, parcelId, event);
    awaitRow(eventId, "SENT");

    assertThat(rowsFor(eventId)).hasSize(1);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification.processed_event WHERE consumer = 'notification-integration-service' AND event_id = ?",
        Integer.class, eventId)).isEqualTo(1);

    double before = registry.counter("zentro.notifications.duplicates").count();

    // Replay the same event twice
    send(STATUS_TOPIC, parcelId, event);
    send(STATUS_TOPIC, parcelId, event);

    // Send a distinct marker event on the same partition to ensure replay records are consumed
    UUID markerEventId = UUID.randomUUID();
    send(STATUS_TOPIC, parcelId, statusEvent(markerEventId, parcelId, ref, "FAILED", "Customer refused"));
    awaitRow(markerEventId, "SENT");

    await().atMost(Duration.ofSeconds(30))
        .until(() -> registry.counter("zentro.notifications.duplicates").count() >= before + 2);

    // Assert that the replayed event has strictly 1 attempt and 1 processed_event record
    assertThat(rowsFor(eventId)).hasSize(1);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification.processed_event WHERE consumer = 'notification-integration-service' AND event_id = ?",
        Integer.class, eventId)).isEqualTo(1);
  }

  @Test
  @DisplayName("A malformed message is recorded as rejected and the next valid event is still processed")
  void malformedIsRejectedAndDoesNotBlock() throws Exception {
    UUID parcelId = UUID.randomUUID();
    UUID next = UUID.randomUUID();
    long rejectedBefore = jdbc.queryForObject("SELECT COUNT(*) FROM notification.rejected_event", Long.class);

    send(STATUS_TOPIC, parcelId, "{\"bad\":\"json\"}");
    send(STATUS_TOPIC, parcelId, statusEvent(next, parcelId, newTrackingRef(), "OUT_FOR_DELIVERY", null));

    awaitRow(next, "SENT");
    await().atMost(Duration.ofSeconds(30)).until(() ->
        jdbc.queryForObject("SELECT COUNT(*) FROM notification.rejected_event", Long.class) > rejectedBefore);
    Map<String, Object> rejected = jdbc.queryForMap(
        "SELECT topic, partition_no, offset_no, reason FROM notification.rejected_event ORDER BY received_at DESC LIMIT 1");
    assertThat(rejected.get("topic")).isEqualTo(STATUS_TOPIC);
    assertThat(rejected.get("reason")).isEqualTo("missing eventId");
    assertThat(rejected.get("offset_no")).isNotNull();
    assertThat(rejected.toString()).doesNotContain("bad");
  }

  @Test
  @DisplayName("No new endpoints: unknown paths need authentication; health stays public")
  void securityUnchanged() throws Exception {
    mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }
}
