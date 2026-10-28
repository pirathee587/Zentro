package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.service.PricingService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * Runs the booking flow against a throwaway PostgreSQL container with the real V1 and V2 migrations.
 * Never touches the shared database. Skipped when Docker is not available on the machine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ParcelBookingIntegrationTest {
  private static final String URL = "/api/v1/parcels";
  private static final String BODY = """
      {
        "sender": {"name": "Test Sender", "phone": "0771234567", "address": "12 Test Road, Colombo", "city": "Colombo"},
        "recipient": {"name": "Test Recipient", "phone": "+94771234567", "address": "45 Test Street, Kandy", "city": "Kandy"},
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
  @SpyBean PricingService pricingService;
  @MockBean JwtDecoder jwtDecoder;

  @BeforeEach
  void clearBookings() {
    jdbc.update("DELETE FROM parcel.parcel");
  }

  @Test
  @DisplayName("V1 and V2 migrations are applied")
  void migrationsApplied() {
    List<String> versions = jdbc.queryForList(
        "SELECT version FROM pricing.flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
    assertThat(versions).contains("1", "2");
  }

  @Test
  @DisplayName("Booking is stored with status CREATED and the fee from the seeded pricing rule")
  void bookingIsPersisted() throws Exception {
    String response = mvc.perform(post(URL).with(customer("it-customer-1"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andReturn().getResponse().getContentAsString();
    JsonNode booking = objectMapper.readTree(response);

    // Seeded STANDARD_DEFAULT: 550 base + 1 extra kg x 120 + 402 other-district surcharge = 1072.00
    Map<String, Object> row = jdbc.queryForMap(
        "SELECT tracking_ref, customer_subject, status, fee, currency, estimated_days, sender_phone "
            + "FROM parcel.parcel WHERE id = ?::uuid", booking.get("id").asText());
    assertThat(row.get("status")).isEqualTo("CREATED");
    assertThat((BigDecimal) row.get("fee")).isEqualByComparingTo("1072.00");
    assertThat(booking.get("fee").decimalValue()).isEqualByComparingTo("1072.00");
    assertThat(row.get("currency")).isEqualTo("LKR");
    assertThat(row.get("estimated_days")).isEqualTo(3);
    assertThat(row.get("customer_subject")).isEqualTo("it-customer-1");
    assertThat(row.get("tracking_ref")).isEqualTo(booking.get("trackingRef").asText());
    assertThat((String) row.get("tracking_ref")).matches("^ZT-[0-9A-HJKMNP-TV-Z]{10}$");
  }

  @Test
  @DisplayName("Two bookings get different tracking references")
  void trackingRefsAreUnique() throws Exception {
    for (int i = 0; i < 2; i++) {
      mvc.perform(post(URL).with(customer("it-customer-1")).contentType(MediaType.APPLICATION_JSON).content(BODY))
          .andExpect(status().isCreated());
    }
    Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT tracking_ref) FROM parcel.parcel", Integer.class);
    assertThat(distinct).isEqualTo(2);
  }

  @Test
  @DisplayName("Same Idempotency-Key stores one row; a different body with the same key is rejected")
  void idempotencyKeyStoresOneRow() throws Exception {
    String first = mvc.perform(post(URL).with(customer("it-customer-1")).header("Idempotency-Key", "it-key-1")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String second = mvc.perform(post(URL).with(customer("it-customer-1")).header("Idempotency-Key", "it-key-1")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    assertThat(objectMapper.readTree(second).get("trackingRef"))
        .isEqualTo(objectMapper.readTree(first).get("trackingRef"));

    mvc.perform(post(URL).with(customer("it-customer-1")).header("Idempotency-Key", "it-key-1")
            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("2.0", "3.0")))
        .andExpect(status().isConflict());

    assertThat(count()).isEqualTo(1);
  }

  @Test
  @DisplayName("Pricing failure returns 503 and stores nothing")
  void pricingFailureStoresNothing() throws Exception {
    doThrow(new QueryTimeoutException("pricing down")).when(pricingService).estimateFee(any());

    mvc.perform(post(URL).with(customer("it-customer-1")).header("Idempotency-Key", "it-key-2")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isServiceUnavailable());

    assertThat(count()).isZero();
  }

  @Test
  @DisplayName("Invalid booking returns 422 and stores nothing")
  void invalidBookingStoresNothing() throws Exception {
    mvc.perform(post(URL).with(customer("it-customer-1"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("0771234567", "12345")))
        .andExpect(status().isUnprocessableEntity());

    assertThat(count()).isZero();
  }

  private int count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel", Integer.class);
  }

  private static RequestPostProcessor customer(String subject) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }
}
