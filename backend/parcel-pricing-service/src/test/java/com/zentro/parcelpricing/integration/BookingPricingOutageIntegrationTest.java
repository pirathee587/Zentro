package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.pricing.service.PricingService;
import java.math.BigDecimal;
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
 * What a booking does when pricing cannot give a usable fee: no parcel row and no outbox event, with a clear
 * error. Also records how PricingService behaves when reading the pricing rule fails (it falls back to the
 * default rates) - a finding for the pricing owner, documented here and not changed.
 * Never touches the shared database. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingPricingOutageIntegrationTest {
  private static final String BODY = """
      {
        "sender": {"name": "Outage Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Outage Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Small Box", "size": "SMALL"},
        "destinationType": "SAME_CITY"
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

  private MockHttpServletResponse book(String subject) throws Exception {
    return mvc.perform(post("/api/v1/parcels")
            .with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
            .header("Idempotency-Key", subject + "-key")
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andReturn().getResponse();
  }

  private void assertNothingStored(String subject) {
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel WHERE customer_subject = ?", Integer.class,
        subject)).isZero();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.outbox_event e
         WHERE NOT EXISTS (SELECT 1 FROM parcel.parcel p WHERE p.id = e.aggregate_id)
        """, Integer.class)).isZero();
  }

  private int outboxRows() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.outbox_event", Integer.class);
  }

  @Test
  @DisplayName("When pricing fails, the booking returns 503 and stores no parcel and no event")
  void pricingFailureReturns503AndStoresNothing() throws Exception {
    int eventsBefore = outboxRows();
    doThrow(new QueryTimeoutException("pricing database timeout")).when(pricingService).estimateFee(any());

    MockHttpServletResponse response = book("outage-1");

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(response.getContentAsString()).doesNotContain("timeout", "Outage Sender", "0771234567");
    assertNothingStored("outage-1");
    assertThat(outboxRows()).isEqualTo(eventsBefore);
  }

  @Test
  @DisplayName("When pricing rejects the parcel data, the booking returns 422 and stores nothing")
  void pricingRejectionReturns422AndStoresNothing() throws Exception {
    int eventsBefore = outboxRows();
    doThrow(new IllegalArgumentException("Unsupported currency 'USD'. Expected 'LKR'."))
        .when(pricingService).estimateFee(any());

    MockHttpServletResponse response = book("outage-2");

    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertNothingStored("outage-2");
    assertThat(outboxRows()).isEqualTo(eventsBefore);
  }

  @Test
  @DisplayName("An unusable active pricing rule (zero base fee) returns 503 and stores nothing")
  void unusablePricingRuleReturns503() throws Exception {
    int eventsBefore = outboxRows();
    BigDecimal originalBaseFee = jdbc.queryForObject(
        "SELECT base_fee FROM pricing.pricing_rule WHERE rule_name = 'STANDARD_DEFAULT'", BigDecimal.class);
    jdbc.update("UPDATE pricing.pricing_rule SET base_fee = 0 WHERE rule_name = 'STANDARD_DEFAULT'");
    try {
      MockHttpServletResponse response = book("outage-3");

      assertThat(response.getStatus()).isEqualTo(503);
      assertNothingStored("outage-3");
      assertThat(outboxRows()).isEqualTo(eventsBefore);
    } finally {
      jdbc.update("UPDATE pricing.pricing_rule SET base_fee = ? WHERE rule_name = 'STANDARD_DEFAULT'", originalBaseFee);
    }
  }

  @Test
  @DisplayName("Finding: if the pricing rule cannot be read, bookings are priced with the default rates instead of failing")
  void unreadablePricingRuleFallsBackToDefaultRates() throws Exception {
    BigDecimal originalBaseFee = jdbc.queryForObject(
        "SELECT base_fee FROM pricing.pricing_rule WHERE rule_name = 'STANDARD_DEFAULT'", BigDecimal.class);
    // An operator-configured rate: 600 base instead of the default 550.
    jdbc.update("UPDATE pricing.pricing_rule SET base_fee = 600, updated_at = now() WHERE rule_name = 'STANDARD_DEFAULT'");
    try {
      MockHttpServletResponse configured = book("outage-4a");
      assertThat(configured.getStatus()).isEqualTo(201);
      assertThat(objectMapper.readTree(configured.getContentAsString()).get("fee").decimalValue())
          .isEqualByComparingTo("720.00"); // 600 + 1 extra kg x 120

      // Make the rule unreadable (as if the pricing table were unavailable) while the parcel tables still work.
      jdbc.execute("ALTER TABLE pricing.pricing_rule RENAME TO pricing_rule_unavailable");
      try {
        MockHttpServletResponse fallback = book("outage-4b");

        // Current behaviour: the booking succeeds at the built-in default rate (550 base), not the configured 600.
        assertThat(fallback.getStatus()).isEqualTo(201);
        assertThat(objectMapper.readTree(fallback.getContentAsString()).get("fee").decimalValue())
            .isEqualByComparingTo("670.00");
      } finally {
        jdbc.execute("ALTER TABLE pricing.pricing_rule_unavailable RENAME TO pricing_rule");
      }
    } finally {
      jdbc.update("UPDATE pricing.pricing_rule SET base_fee = ? WHERE rule_name = 'STANDARD_DEFAULT'", originalBaseFee);
    }
  }
}
