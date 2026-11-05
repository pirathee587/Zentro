package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
 * A parcel created by the booking API is left in a valid lifecycle state: a dispatcher can move it from
 * CREATED to PICKED_UP through the status endpoint, and it cannot skip steps.
 * Never touches the shared database. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingLifecycleStartIntegrationTest {
  private static final String BODY = """
      {
        "sender": {"name": "Life Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Life Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
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

  private static RequestPostProcessor as(String subject, String role) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private String book(String customer) throws Exception {
    String body = mvc.perform(post("/api/v1/parcels").with(as(customer, "CUSTOMER"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body).get("id").asText();
  }

  private ResultActions move(String parcelId, String newStatus, RequestPostProcessor actor) throws Exception {
    return mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status").with(actor)
        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + newStatus + "\"}"));
  }

  @Test
  @DisplayName("A newly booked parcel can be moved from CREATED to PICKED_UP by a dispatcher")
  void bookedParcelCanBePickedUp() throws Exception {
    String parcelId = book("life-customer-1");

    move(parcelId, "PICKED_UP", as("life-dispatcher", "DISPATCHER"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.previousStatus").value("CREATED"))
        .andExpect(jsonPath("$.status").value("PICKED_UP"))
        .andExpect(jsonPath("$.changed").value(true));

    Map<String, Object> parcel = jdbc.queryForMap("SELECT status, version FROM parcel.parcel WHERE id = ?::uuid",
        parcelId);
    assertThat(parcel).containsEntry("status", "PICKED_UP").containsEntry("version", 1L);

    List<Map<String, Object>> history = jdbc.queryForList("""
        SELECT from_status, to_status, actor_role, actor_subject FROM parcel.parcel_status_history
         WHERE parcel_id = ?::uuid
        """, parcelId);
    assertThat(history).singleElement().satisfies(row -> {
      assertThat(row).containsEntry("from_status", "CREATED").containsEntry("to_status", "PICKED_UP");
      assertThat(row).containsEntry("actor_role", "DISPATCHER").containsEntry("actor_subject", "life-dispatcher");
    });

    List<String> events = jdbc.queryForList(
        "SELECT event_type FROM parcel.outbox_event WHERE aggregate_id = ?::uuid ORDER BY occurred_at",
        String.class, parcelId);
    assertThat(events).containsExactly("parcel.created", "parcel.status.updated");
  }

  @Test
  @DisplayName("A newly booked parcel cannot skip steps: CREATED -> DELIVERED is rejected with 409")
  void bookedParcelCannotSkipSteps() throws Exception {
    String parcelId = book("life-customer-2");

    move(parcelId, "DELIVERED", as("life-dispatcher", "DISPATCHER"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
        .andExpect(jsonPath("$.currentStatus").value("CREATED"))
        .andExpect(jsonPath("$.allowedNext[0]").value("PICKED_UP"));

    assertThat(jdbc.queryForObject("SELECT status FROM parcel.parcel WHERE id = ?::uuid", String.class, parcelId))
        .isEqualTo("CREATED");
  }

  @Test
  @DisplayName("The customer who booked the parcel cannot change its status (403)")
  void customerCannotMoveOwnParcel() throws Exception {
    String parcelId = book("life-customer-3");

    move(parcelId, "PICKED_UP", as("life-customer-3", "CUSTOMER")).andExpect(status().isForbidden());

    assertThat(jdbc.queryForObject("SELECT status FROM parcel.parcel WHERE id = ?::uuid", String.class, parcelId))
        .isEqualTo("CREATED");
  }
}
