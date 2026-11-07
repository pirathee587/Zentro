package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.integration.support.OutboxFaultTriggers;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ZD-062: a status change, its history row and its parcel.status.updated outbox row are committed together or not at
 * all. Failures are injected with real PostgreSQL triggers ({@link OutboxFaultTriggers}); assertions read the tables.
 * The outbox-insert failure and repeat/invalid/terminal requests are already covered by ParcelStatusIntegrationTest.
 * Never touches the shared database; skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ParcelStatusOutboxAtomicityIntegrationTest {
  private static final String BOOKING = """
      {
        "sender": {"name": "Status Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Status Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
        "parcel": {"weightKg": 1.0, "category": "Documents", "size": "SMALL"}
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
  @MockBean JwtDecoder jwtDecoder;

  private static RequestPostProcessor as(String subject, String role) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private UUID book() throws Exception {
    MockHttpServletResponse response = mvc.perform(post("/api/v1/parcels").with(as("atomic-status-customer", "CUSTOMER"))
        .contentType(MediaType.APPLICATION_JSON).content(BOOKING)).andReturn().getResponse();
    assertThat(response.getStatus()).isEqualTo(201);
    return UUID.fromString(objectMapper.readTree(response.getContentAsString()).get("id").asText());
  }

  private MockHttpServletResponse move(UUID parcelId, String json) throws Exception {
    return mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status").with(as("atomic-dispatcher", "DISPATCHER"))
        .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn().getResponse();
  }

  private MockHttpServletResponse move(UUID parcelId, String status, String reason) throws Exception {
    return move(parcelId, reason == null
        ? "{\"status\":\"" + status + "\"}"
        : "{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\"}");
  }

  private Map<String, Object> parcel(UUID parcelId) {
    return jdbc.queryForMap("SELECT status, version FROM parcel.parcel WHERE id = ?", parcelId);
  }

  private int history(UUID parcelId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel_status_history WHERE parcel_id = ?",
        Integer.class, parcelId);
  }

  private int statusEvents(UUID parcelId) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.outbox_event WHERE aggregate_id = ? AND event_type = 'parcel.status.updated'
        """, Integer.class, parcelId);
  }

  private void assertUnchanged(UUID parcelId, String status, long version, int historyRows, int events) {
    assertThat(parcel(parcelId)).containsEntry("status", status).containsEntry("version", version);
    assertThat(history(parcelId)).isEqualTo(historyRows);
    assertThat(statusEvents(parcelId)).isEqualTo(events);
  }

  @Test
  @DisplayName("History insert fails: 503, status and version unchanged, no history row and no event")
  void historyInsertFailure() throws Exception {
    UUID parcelId = book();

    MockHttpServletResponse response;
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectInsert(jdbc, "parcel.parcel_status_history")) {
      response = move(parcelId, "PICKED_UP", null);
    }

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).contains("STATUS_UPDATE_UNAVAILABLE").doesNotContain(OutboxFaultTriggers.MESSAGE);
    assertUnchanged(parcelId, "CREATED", 0L, 0, 0);
  }

  @Test
  @DisplayName("All writes run but the commit fails: 503 and the status, history and event are all rolled back")
  void commitFailureKeepsNothing() throws Exception {
    UUID parcelId = book();

    MockHttpServletResponse response;
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectAtCommit(jdbc, "parcel.outbox_event")) {
      response = move(parcelId, "PICKED_UP", null);
    }

    assertThat(response.getStatus()).isEqualTo(503);
    assertUnchanged(parcelId, "CREATED", 0L, 0, 0);
  }

  @Test
  @DisplayName("A failed change leaves the parcel usable: the same change then succeeds once with one event")
  void retryAfterFailureChangesOnce() throws Exception {
    UUID parcelId = book();
    assertThat(move(parcelId, "PICKED_UP", null).getStatus()).isEqualTo(200);
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectInsert(jdbc, "parcel.outbox_event")) {
      assertThat(move(parcelId, "FAILED", "Recipient not available").getStatus()).isEqualTo(503);
    }
    assertUnchanged(parcelId, "PICKED_UP", 1L, 1, 1);

    MockHttpServletResponse retry = move(parcelId, "FAILED", "Recipient not available");

    assertThat(retry.getStatus()).isEqualTo(200);
    assertUnchanged(parcelId, "FAILED", 2L, 2, 2);
    assertThat(jdbc.queryForObject("""
        SELECT payload->'payload'->>'failureReason' FROM parcel.outbox_event
         WHERE aggregate_id = ? AND payload->'payload'->>'status' = 'FAILED'
        """, String.class, parcelId)).isEqualTo("Recipient not available");
  }

  @Test
  @DisplayName("Competing parallel changes: every accepted change has exactly one history row and one event")
  void competingChanges() throws Exception {
    UUID parcelId = book();
    assertThat(move(parcelId, "PICKED_UP", null).getStatus()).isEqualTo(200);
    List<String> bodies = List.of(
        "{\"status\":\"IN_TRANSIT\"}", "{\"status\":\"FAILED\",\"reason\":\"Damaged\"}",
        "{\"status\":\"IN_TRANSIT\"}", "{\"status\":\"OUT_FOR_DELIVERY\"}",
        "{\"status\":\"FAILED\",\"reason\":\"Lost\"}", "{\"status\":\"IN_TRANSIT\"}",
        "{\"status\":\"DELIVERED\"}", "{\"status\":\"OUT_FOR_DELIVERY\"}");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
    List<JsonNode> accepted = new ArrayList<>();
    try {
      List<Future<MockHttpServletResponse>> results = new ArrayList<>();
      for (String body : bodies) {
        Callable<MockHttpServletResponse> call = () -> {
          start.await();
          return move(parcelId, body);
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      for (Future<MockHttpServletResponse> result : results) {
        MockHttpServletResponse response = result.get();
        assertThat(response.getStatus()).as(response.getContentAsString()).isIn(200, 409);
        if (response.getStatus() == 200) {
          JsonNode json = objectMapper.readTree(response.getContentAsString());
          if (json.get("changed").asBoolean()) {
            accepted.add(json);
          }
        }
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(accepted).isNotEmpty();
    // PICKED_UP itself is one change; each accepted parallel change adds exactly one history row and one event.
    assertThat(history(parcelId)).isEqualTo(1 + accepted.size());
    assertThat(statusEvents(parcelId)).isEqualTo(1 + accepted.size());
    assertThat(parcel(parcelId)).containsEntry("version", (long) (1 + accepted.size()));
    List<String> eventStatuses = jdbc.queryForList("""
        SELECT payload->'payload'->>'status' FROM parcel.outbox_event
         WHERE aggregate_id = ? AND event_type = 'parcel.status.updated' ORDER BY occurred_at
        """, String.class, parcelId);
    List<String> historyStatuses = jdbc.queryForList(
        "SELECT to_status FROM parcel.parcel_status_history WHERE parcel_id = ? ORDER BY occurred_at",
        String.class, parcelId);
    assertThat(eventStatuses).isEqualTo(historyStatuses);
    assertThat(parcel(parcelId).get("status")).isEqualTo(historyStatuses.get(historyStatuses.size() - 1));
  }
}
