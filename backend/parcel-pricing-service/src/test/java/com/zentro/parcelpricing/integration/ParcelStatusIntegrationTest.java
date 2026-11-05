package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.service.ParcelStatusService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the status lifecycle against a throwaway PostgreSQL container with all migrations (V1-V5).
 * Never touches the shared database. Skipped when Docker is not available on the machine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ParcelStatusIntegrationTest {
  private static final String DISPATCHER = "it-dispatcher-1";
  private static final String BOOKING = """
      {
        "sender": {"name": "Status Sender", "phone": "0771234567", "address": "12 Status Road, Colombo", "city": "Colombo"},
        "recipient": {"name": "Status Recipient", "phone": "+94712345678", "address": "45 Status Street, Kandy", "city": "Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Small Box", "size": "SMALL"},
        "destinationType": "OTHER_DISTRICT"
      }
      """;

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;
  @Autowired ParcelStatusService statusService;
  @MockBean JwtDecoder jwtDecoder;

  private static RequestPostProcessor as(String subject, String role) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private UUID book() throws Exception {
    String body = mvc.perform(post("/api/v1/parcels").with(as("it-customer-1", "CUSTOMER"))
            .contentType(MediaType.APPLICATION_JSON).content(BOOKING))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return UUID.fromString(objectMapper.readTree(body).get("id").asText());
  }

  private ResultActions move(UUID parcelId, String json, RequestPostProcessor auth) throws Exception {
    return mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status").with(auth)
        .contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private ResultActions move(UUID parcelId, String status) throws Exception {
    return move(parcelId, "{\"status\":\"" + status + "\"}", as(DISPATCHER, "DISPATCHER"));
  }

  private int historyCount(UUID parcelId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel_status_history WHERE parcel_id = ?",
        Integer.class, parcelId);
  }

  private int outboxCount(UUID parcelId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.outbox_event WHERE aggregate_id = ?"
        + " AND event_type = 'parcel.status.updated'", Integer.class,
        parcelId);
  }

  private Map<String, Object> parcelRow(UUID parcelId) {
    return jdbc.queryForMap("SELECT status, version FROM parcel.parcel WHERE id = ?", parcelId);
  }

  @Test
  @DisplayName("All migrations V1-V5 are applied")
  void migrationsApplied() {
    List<String> versions = jdbc.queryForList(
        "SELECT version FROM pricing.flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
    assertThat(versions).containsSubsequence("1", "2", "3", "4", "5");
  }

  @Test
  @DisplayName("Full lifecycle CREATED -> DELIVERED records ordered history and one outbox event per change")
  void fullLifecycle() throws Exception {
    UUID parcelId = book();
    String[] path = {"PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED"};
    for (String next : path) {
      move(parcelId, next).andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value(next))
          .andExpect(jsonPath("$.changed").value(true));
    }

    assertThat(parcelRow(parcelId)).containsEntry("status", "DELIVERED").containsEntry("version", 4L);

    List<Map<String, Object>> history = jdbc.queryForList("""
        SELECT from_status, to_status, actor_subject, actor_role, occurred_at
          FROM parcel.parcel_status_history WHERE parcel_id = ? ORDER BY occurred_at, id
        """, parcelId);
    assertThat(history).extracting(r -> r.get("from_status") + "->" + r.get("to_status"))
        .containsExactly("CREATED->PICKED_UP", "PICKED_UP->IN_TRANSIT", "IN_TRANSIT->OUT_FOR_DELIVERY",
            "OUT_FOR_DELIVERY->DELIVERED");
    assertThat(history).allSatisfy(r -> {
      assertThat(r.get("actor_subject")).isEqualTo(DISPATCHER);
      assertThat(r.get("actor_role")).isEqualTo("DISPATCHER");
      assertThat(r.get("occurred_at")).isNotNull();
    });

    List<Map<String, Object>> events = jdbc.queryForList("""
        SELECT event_id, event_type, payload::text AS payload, published_at, attempts
          FROM parcel.outbox_event WHERE aggregate_id = ? AND event_type = 'parcel.status.updated'
         ORDER BY occurred_at
        """, parcelId);
    assertThat(events).hasSize(4);
    List<String> statuses = new ArrayList<>();
    for (Map<String, Object> e : events) {
      assertThat(e.get("event_type")).isEqualTo("parcel.status.updated");
      assertThat(e.get("published_at")).isNull();
      assertThat(e.get("attempts")).isEqualTo(0);
      String payloadText = (String) e.get("payload");
      assertThat(payloadText).doesNotContain("Status Sender", "Status Recipient", "0771234567", "712345678",
          "Status Road", "Status Street", DISPATCHER);
      JsonNode envelope = objectMapper.readTree(payloadText);
      assertThat(envelope.get("eventId").asText()).isEqualTo(e.get("event_id").toString());
      assertThat(envelope.get("aggregateId").asText()).isEqualTo(parcelId.toString());
      statuses.add(envelope.get("payload").get("status").asText());
    }
    assertThat(statuses).containsExactly(path);
  }

  @Test
  @DisplayName("Repeat, invalid and terminal requests record nothing")
  void rejectedAndRepeatedRequestsRecordNothing() throws Exception {
    UUID parcelId = book();
    move(parcelId, "PICKED_UP").andExpect(status().isOk());

    move(parcelId, "PICKED_UP").andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(false));
    move(parcelId, "DELIVERED").andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
        .andExpect(jsonPath("$.currentStatus").value("PICKED_UP"));
    move(parcelId, "{\"status\":\"FAILED\"}", as(DISPATCHER, "DISPATCHER"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors.reason").exists());
    assertThat(historyCount(parcelId)).isEqualTo(1);
    assertThat(outboxCount(parcelId)).isEqualTo(1);
    assertThat(parcelRow(parcelId)).containsEntry("status", "PICKED_UP").containsEntry("version", 1L);

    move(parcelId, "IN_TRANSIT").andExpect(status().isOk());
    move(parcelId, "OUT_FOR_DELIVERY").andExpect(status().isOk());
    move(parcelId, "DELIVERED").andExpect(status().isOk());
    move(parcelId, "{\"status\":\"FAILED\",\"reason\":\"Lost\"}", as(DISPATCHER, "DISPATCHER"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PARCEL_TERMINAL"));
    assertThat(historyCount(parcelId)).isEqualTo(4);
    assertThat(outboxCount(parcelId)).isEqualTo(4);
  }

  /**
   * ZD-25 (Decision 1A): SERVICE is restricted to OUT_FOR_DELIVERY → DELIVERED only.
   * FAILED transitions must be performed by DISPATCHER with an explicit reason.
   * This test verifies:
   *   (a) DISPATCHER can record a FAILED transition with reason and note in the history/outbox.
   *   (b) SERVICE attempting FAILED from any state is rejected with 409 INVALID_TRANSITION.
   */
  @Test
  @DisplayName("FAILED with a reason is recorded by DISPATCHER; SERVICE is rejected with 409")
  void failedPathByDispatcherAndServiceRejected() throws Exception {
    // --- (b) SERVICE cannot record FAILED from any intermediate state ---
    UUID parcelIdService = book();
    move(parcelIdService, "PICKED_UP").andExpect(status().isOk());
    move(parcelIdService, "{\"status\":\"FAILED\",\"reason\":\"Recipient not available\",\"note\":\"Gate locked\"}",
        as("service-account-s3", "SERVICE"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    // No history or outbox entry beyond the initial PICKED_UP move
    assertThat(historyCount(parcelIdService)).isEqualTo(1);
    assertThat(outboxCount(parcelIdService)).isEqualTo(1);

    // --- (a) DISPATCHER can record FAILED with a reason and note ---
    UUID parcelId = book();
    move(parcelId, "PICKED_UP").andExpect(status().isOk());
    move(parcelId, "{\"status\":\"FAILED\",\"reason\":\"Recipient not available\",\"note\":\"Gate locked\"}",
        as(DISPATCHER, "DISPATCHER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"));

    Map<String, Object> last = jdbc.queryForMap("""
        SELECT to_status, reason, note, actor_subject, actor_role FROM parcel.parcel_status_history
         WHERE parcel_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1
        """, parcelId);
    assertThat(last).containsEntry("to_status", "FAILED").containsEntry("reason", "Recipient not available")
        .containsEntry("note", "Gate locked").containsEntry("actor_subject", DISPATCHER)
        .containsEntry("actor_role", "DISPATCHER");

    String payload = jdbc.queryForObject("""
        SELECT payload::text FROM parcel.outbox_event WHERE aggregate_id = ? AND event_type = 'parcel.status.updated'
         ORDER BY occurred_at DESC LIMIT 1
        """, String.class, parcelId);
    JsonNode event = objectMapper.readTree(payload).get("payload");
    assertThat(event.get("failureReason").asText()).isEqualTo("Recipient not available");
    assertThat(event.get("actorRole").asText()).isEqualTo("DISPATCHER");
    assertThat(payload).doesNotContain("Gate locked", DISPATCHER);
  }

  @Test
  @DisplayName("Status history rows cannot be updated or deleted")
  void historyIsImmutable() throws Exception {
    UUID parcelId = book();
    move(parcelId, "PICKED_UP").andExpect(status().isOk());

    assertThatThrownBy(() -> jdbc.update("UPDATE parcel.parcel_status_history SET note = 'edited' WHERE parcel_id = ?",
        parcelId)).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM parcel.parcel_status_history WHERE parcel_id = ?", parcelId))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    assertThat(historyCount(parcelId)).isEqualTo(1);
  }

  @Test
  @DisplayName("Two concurrent identical updates record exactly one change")
  void concurrentUpdatesRecordOneChange() throws Exception {
    UUID parcelId = book();
    int threads = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<ParcelStatusResponse>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        Callable<ParcelStatusResponse> call = () -> {
          start.await();
          return statusService.updateStatus(parcelId, ParcelStatus.PICKED_UP, null, null, DISPATCHER, "DISPATCHER");
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      int changed = 0;
      for (Future<ParcelStatusResponse> r : results) {
        if (r.get().changed()) {
          changed++;
        }
      }
      assertThat(changed).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(historyCount(parcelId)).isEqualTo(1);
    assertThat(outboxCount(parcelId)).isEqualTo(1);
    assertThat(parcelRow(parcelId)).containsEntry("status", "PICKED_UP").containsEntry("version", 1L);
  }

  @Test
  @DisplayName("If the outbox write fails, the status and history are rolled back")
  void outboxFailureRollsBack() throws Exception {
    UUID parcelId = book();
    // Make the real database reject outbox inserts for the duration of this test.
    jdbc.execute("""
        CREATE FUNCTION parcel.test_reject_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'outbox unavailable (test)'; END; $$
        """);
    jdbc.execute("""
        CREATE TRIGGER test_reject_outbox BEFORE INSERT ON parcel.outbox_event
        FOR EACH ROW EXECUTE FUNCTION parcel.test_reject_outbox()
        """);
    try {
      move(parcelId, "PICKED_UP").andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.code").value("STATUS_UPDATE_UNAVAILABLE"));
    } finally {
      jdbc.execute("DROP TRIGGER test_reject_outbox ON parcel.outbox_event");
      jdbc.execute("DROP FUNCTION parcel.test_reject_outbox()");
    }

    assertThat(parcelRow(parcelId)).containsEntry("status", "CREATED").containsEntry("version", 0L);
    assertThat(historyCount(parcelId)).isZero();
    assertThat(outboxCount(parcelId)).isZero();
  }

  @Test
  @DisplayName("Unknown parcel returns 404 and a customer token returns 403")
  void notFoundAndForbidden() throws Exception {
    move(UUID.randomUUID(), "PICKED_UP").andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PARCEL_NOT_FOUND"));

    UUID parcelId = book();
    move(parcelId, "{\"status\":\"PICKED_UP\"}", as("it-customer-1", "CUSTOMER")).andExpect(status().isForbidden());
    assertThat(parcelRow(parcelId)).containsEntry("status", "CREATED");
  }
}
