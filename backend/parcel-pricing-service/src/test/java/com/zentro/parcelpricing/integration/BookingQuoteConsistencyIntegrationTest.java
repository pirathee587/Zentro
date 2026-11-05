package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The fee a customer is quoted (POST /api/v1/parcels/estimate, called by the Send parcel form) must be the fee
 * the booking stores and returns for the same parcel. Uses the real seeded pricing rule; nothing is mocked.
 * Never touches the shared database. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingQuoteConsistencyIntegrationTest {
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

  private static RequestPostProcessor customer() {
    return jwt().jwt(j -> j.subject("quote-customer")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }

  /** The estimate request exactly as the Send parcel form builds it, plus isExpress. */
  private JsonNode quote(String weight, String destinationType, String size, boolean express) throws Exception {
    ObjectNode request = objectMapper.createObjectNode()
        .put("weightKg", new BigDecimal(weight))
        .put("destinationType", destinationType)
        .put("parcelSize", size)
        .put("isExpress", express);
    String body = mvc.perform(post("/api/v1/parcels/estimate").with(customer())
            .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body);
  }

  /** The booking request as the form builds it for the same parcel. */
  private JsonNode book(String weight, String destinationType, String size, boolean express) throws Exception {
    ObjectNode request = objectMapper.createObjectNode();
    request.putObject("sender").put("name", "Sender Contact").put("phone", "0771234567")
        .put("address", "12 Galle Road, Colombo 03");
    request.putObject("recipient").put("name", "Quote Recipient").put("phone", "0712345678")
        .put("address", "45 Temple Street, Kandy");
    request.putObject("parcel").put("weightKg", new BigDecimal(weight)).put("category", "Small Box")
        .put("size", size);
    request.put("destinationType", destinationType).put("isExpress", express);
    String body = mvc.perform(post("/api/v1/parcels").with(customer())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(MediaType.APPLICATION_JSON).content(request.toString()))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(body);
  }

  private BigDecimal storedFee(JsonNode booking) {
    return jdbc.queryForObject("SELECT fee FROM parcel.parcel WHERE id = ?::uuid", BigDecimal.class,
        booking.get("id").asText());
  }

  @ParameterizedTest(name = "{0} kg, {1}, {2}, express={3} -> LKR {4}, {5} days")
  @CsvSource({
      "2.0,  SAME_CITY,      SMALL,  false, 670.00,  2",
      "2.0,  OTHER_DISTRICT, MEDIUM, false, 1072.00, 3",
      "3.5,  SAME_CITY,      MEDIUM, true,  1150.00, 1",
      "0.1,  SAME_CITY,      SMALL,  false, 550.00,  2",
      "1.0,  OTHER_DISTRICT, SMALL,  false, 952.00,  3",
      "20,   OTHER_DISTRICT, LARGE,  true,  3532.00, 1",
      "7.25, SAME_CITY,      LARGE,  false, 1300.00, 2"
  })
  @DisplayName("The booked fee, currency and estimate equal the quotation for the same parcel")
  void bookedFeeMatchesQuote(String weight, String destinationType, String size, boolean express,
      String expectedFee, int expectedDays) throws Exception {
    JsonNode quote = quote(weight, destinationType, size, express);
    JsonNode booking = book(weight, destinationType, size, express);

    assertThat(quote.get("fee").decimalValue()).isEqualByComparingTo(expectedFee);
    assertThat(booking.get("fee").decimalValue()).isEqualByComparingTo(quote.get("fee").decimalValue());
    assertThat(storedFee(booking)).isEqualByComparingTo(quote.get("fee").decimalValue());
    assertThat(booking.get("currency").asText()).isEqualTo(quote.get("currency").asText()).isEqualTo("LKR");
    assertThat(booking.get("estimatedDays").asInt()).isEqualTo(quote.get("estimatedDays").asInt())
        .isEqualTo(expectedDays);
  }

  @Test
  @DisplayName("A weight with more than two decimals is quoted and booked at the same fee")
  void weightWithThreeDecimalsKeepsTheQuotedFee() throws Exception {
    JsonNode quote = quote("2.555", "SAME_CITY", "SMALL", false);
    JsonNode booking = book("2.555", "SAME_CITY", "SMALL", false);

    assertThat(booking.get("fee").decimalValue())
        .as("quoted %s but booked %s", quote.get("fee"), booking.get("fee"))
        .isEqualByComparingTo(quote.get("fee").decimalValue());
    assertThat(storedFee(booking)).isEqualByComparingTo(quote.get("fee").decimalValue());
  }
}
