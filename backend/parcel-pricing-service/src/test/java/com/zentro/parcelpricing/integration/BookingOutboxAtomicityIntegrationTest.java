package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.integration.support.OutboxFaultTriggers;
import java.util.UUID;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ZD-062: a customer booking and its parcel.created outbox row are committed together or not at all. Failures are
 * injected with real PostgreSQL triggers ({@link OutboxFaultTriggers}); assertions read the tables directly.
 * Rejected bookings, replays and parallel duplicates are already covered by BookingPricingOutageIntegrationTest and
 * BookingDuplicateSubmissionIntegrationTest. Never touches the shared database; skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingOutboxAtomicityIntegrationTest {
  private static final String BODY = """
      {
        "sender": {"name": "Atomic Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Atomic Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
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
  @MockBean JwtDecoder jwtDecoder;

  private MockHttpServletResponse book(String subject, String idempotencyKey) throws Exception {
    var request = post("/api/v1/parcels")
        .with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType(MediaType.APPLICATION_JSON).content(BODY);
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private int parcels(String subject) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel WHERE customer_subject = ?", Integer.class, subject);
  }

  private int allEvents() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.outbox_event", Integer.class);
  }

  private int orphanEvents() {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.outbox_event e
         WHERE NOT EXISTS (SELECT 1 FROM parcel.parcel p WHERE p.id = e.aggregate_id)
        """, Integer.class);
  }

  private static String subject() {
    return "atomic-" + UUID.randomUUID();
  }

  @Test
  @DisplayName("Outbox insert fails: the booking returns 503 and neither the parcel nor an event is stored")
  void outboxInsertFailure() throws Exception {
    String subject = subject();
    int eventsBefore = allEvents();

    MockHttpServletResponse response;
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectInsert(jdbc, "parcel.outbox_event")) {
      response = book(subject, null);
    }

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).doesNotContain(OutboxFaultTriggers.MESSAGE, "outbox_event");
    assertThat(parcels(subject)).isZero();
    assertThat(allEvents()).isEqualTo(eventsBefore);
  }

  @Test
  @DisplayName("Parcel insert fails: no event is written for a booking that was never stored")
  void parcelInsertFailure() throws Exception {
    String subject = subject();
    int eventsBefore = allEvents();

    MockHttpServletResponse response;
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectInsert(jdbc, "parcel.parcel")) {
      response = book(subject, null);
    }

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(parcels(subject)).isZero();
    assertThat(allEvents()).isEqualTo(eventsBefore);
  }

  @Test
  @DisplayName("Both inserts run but the commit fails: 503 and nothing is kept")
  void commitFailureKeepsNothing() throws Exception {
    String subject = subject();
    int eventsBefore = allEvents();

    MockHttpServletResponse response;
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectAtCommit(jdbc, "parcel.outbox_event")) {
      response = book(subject, null);
    }

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).doesNotContain(OutboxFaultTriggers.MESSAGE, "Atomic Sender", "0771234567");
    assertThat(parcels(subject)).isZero();
    assertThat(allEvents()).isEqualTo(eventsBefore);
  }

  @Test
  @DisplayName("After a failed attempt the same Idempotency-Key books once, with exactly one event")
  void retryAfterFailureBooksOnce() throws Exception {
    String subject = subject();
    String key = UUID.randomUUID().toString();
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectInsert(jdbc, "parcel.outbox_event")) {
      assertThat(book(subject, key).getStatus()).isEqualTo(503);
    }

    MockHttpServletResponse retry = book(subject, key);
    MockHttpServletResponse replay = book(subject, key);

    assertThat(retry.getStatus()).isEqualTo(201);
    assertThat(replay.getStatus()).isEqualTo(200);
    UUID parcelId = UUID.fromString(objectMapper.readTree(retry.getContentAsString()).get("id").asText());
    assertThat(parcels(subject)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parcel.outbox_event WHERE aggregate_id = ?",
        Integer.class, parcelId)).isEqualTo(1);
  }

  @Test
  @DisplayName("Every successful booking has exactly one parcel.created event and no event lacks its parcel")
  void everyBookingHasExactlyOneEvent() throws Exception {
    String subject = subject();
    for (int i = 0; i < 5; i++) {
      assertThat(book(subject, null).getStatus()).isEqualTo(201);
    }
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectAtCommit(jdbc, "parcel.outbox_event")) {
      assertThat(book(subject, null).getStatus()).isEqualTo(503);
    }

    assertThat(parcels(subject)).isEqualTo(5);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.parcel p
         WHERE p.customer_subject = ?
           AND (SELECT COUNT(*) FROM parcel.outbox_event e
                 WHERE e.aggregate_id = p.id AND e.event_type = 'parcel.created') = 1
        """, Integer.class, subject)).isEqualTo(5);
    assertThat(orphanEvents()).isZero();
  }
}
