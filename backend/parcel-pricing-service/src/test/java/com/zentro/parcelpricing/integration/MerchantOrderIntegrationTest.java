package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the merchant order flow against a PostgreSQL test container with Flyway migrations up to V6.
 * Validates database constraints, duplicate handling, and merchant scoping.
 * Automatically skipped when Docker is not present on the environment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MerchantOrderIntegrationTest {
  private static final String URL = "/api/v1/merchant/orders";
  private static final String BODY = """
      {
        "externalOrderRef": "ORD-IT-001",
        "sender": {"name": "Test Merchant Store", "phone": "0112345678", "address": "12 Main Street, Colombo", "city": "Colombo"},
        "recipient": {"name": "Test Customer", "phone": "+94771234567", "address": "45 Temple Road, Kandy", "city": "Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Electronics", "size": "MEDIUM"},
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
  @MockBean IdentityMerchantClient identityClient;
  @MockBean JwtDecoder jwtDecoder;

  @BeforeEach
  void clearOrders() {
    jdbc.update("DELETE FROM parcel.parcel");
  }

  @Test
  @DisplayName("V6 migration is applied successfully")
  void v6MigrationApplied() {
    List<String> versions = jdbc.queryForList(
        "SELECT version FROM pricing.flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
    assertThat(versions).contains("1", "2", "3", "4", "5", "6");
  }

  @Test
  @DisplayName("Merchant order is stored in parcel.parcel with status CREATED and calculated fee")
  void orderIsPersisted() throws Exception {
    String response = mvc.perform(post(URL).with(merchant("merchant-it-1"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andExpect(header().exists("Location"))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.externalOrderRef").value("ORD-IT-001"))
        .andExpect(jsonPath("$.merchantId").value("merchant-it-1"))
        .andReturn().getResponse().getContentAsString();

    JsonNode order = objectMapper.readTree(response);
    Map<String, Object> row = jdbc.queryForMap(
        "SELECT tracking_ref, customer_subject, merchant_id, external_order_ref, status, fee, currency "
            + "FROM parcel.parcel WHERE id = ?::uuid", order.get("id").asText());

    assertThat(row.get("merchant_id")).isEqualTo("merchant-it-1");
    assertThat(row.get("external_order_ref")).isEqualTo("ORD-IT-001");
    assertThat(row.get("status")).isEqualTo("CREATED");
    assertThat(row.get("fee")).isNotNull();
    assertThat(row.get("tracking_ref")).asString().startsWith("ZT-");
  }

  @Test
  @DisplayName("Idempotent replay of same externalOrderRef returns 200 without creating second row")
  void idempotentReplayPersisted() throws Exception {
    mvc.perform(post(URL).with(merchant("merchant-it-1"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated());

    mvc.perform(post(URL).with(merchant("merchant-it-1"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.externalOrderRef").value("ORD-IT-001"));

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel WHERE merchant_id = 'merchant-it-1'", Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("Duplicate externalOrderRef with modified body returns 409 Conflict")
  void duplicateExternalRefConflict() throws Exception {
    mvc.perform(post(URL).with(merchant("merchant-it-1"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated());

    String modifiedBody = BODY.replace("Test Customer", "Modified Customer Name");
    mvc.perform(post(URL).with(merchant("merchant-it-1"))
            .contentType(MediaType.APPLICATION_JSON).content(modifiedBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Duplicate external reference"));
  }

  @Test
  @DisplayName("Two different merchants can use the same externalOrderRef independently")
  void differentMerchantsIndependentExternalRef() throws Exception {
    mvc.perform(post(URL).with(merchant("merchant-alpha"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated());

    mvc.perform(post(URL).with(merchant("merchant-beta"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated());

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel WHERE external_order_ref = 'ORD-IT-001'", Integer.class);
    assertThat(count).isEqualTo(2);
  }

  @Test
  @DisplayName("Unapproved merchant is rejected with 403 Forbidden")
  void unapprovedMerchantRejected() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant application is not approved."))
        .when(identityClient).requireApprovedMerchant(any());

    mvc.perform(post(URL).with(merchant("unapproved-merchant"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isForbidden());

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel", Integer.class);
    assertThat(count).isEqualTo(0);
  }

  @Test
  @DisplayName("GET order by ID is scoped to owning merchant")
  void getOrderScoped() throws Exception {
    String response = mvc.perform(post(URL).with(merchant("merchant-owner"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    String id = objectMapper.readTree(response).get("id").asText();

    mvc.perform(get(URL + "/" + id).with(merchant("merchant-owner")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id));

    mvc.perform(get(URL + "/" + id).with(merchant("merchant-other")))
        .andExpect(status().isNotFound());
  }

  private static RequestPostProcessor merchant(String subject) {
    return jwt().jwt(j -> j.subject(subject).tokenValue("integration-test-token"))
        .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
  }
}
