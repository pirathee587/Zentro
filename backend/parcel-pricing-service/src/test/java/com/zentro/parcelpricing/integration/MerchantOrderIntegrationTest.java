package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  void exactMerchantWeightBoundariesAreStored() throws Exception {
    for (String weight : List.of("0.1", "20")) {
      String result = mvc.perform(post(URL).with(merchant("synthetic-boundary-124-" + weight))
          .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("2.0", weight)))
          .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
      String id = objectMapper.readTree(result).get("id").asText();
      assertThat(jdbc.queryForObject("SELECT weight_kg FROM parcel.parcel WHERE id = ?::uuid", java.math.BigDecimal.class, id))
          .isEqualByComparingTo(weight);
    }
  }

  @Test
  void invalidWeightCreatesNoOrderOrOutboxEvent() throws Exception {
    long events = jdbc.queryForObject("SELECT count(*) FROM parcel.outbox_event", Long.class);
    for (String weight : List.of("0.09", "20.01", "1.12345")) {
      mvc.perform(post(URL).with(merchant("synthetic-invalid-124"))
          .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("2.0", weight)))
          .andExpect(status().isUnprocessableEntity());
    }
    for (String address : List.of("short", " ".repeat(20), "synthetic\naddress", "synthetic\u200Baddress", "x".repeat(100000))) {
      var invalid = (com.fasterxml.jackson.databind.node.ObjectNode)objectMapper.readTree(BODY);
      ((com.fasterxml.jackson.databind.node.ObjectNode)invalid.get("sender")).put("address", address);
      mvc.perform(post(URL).with(merchant("synthetic-invalid-124"))
          .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(invalid)))
          .andExpect(status().isUnprocessableEntity());
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.parcel", Long.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.outbox_event", Long.class)).isEqualTo(events);
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

  @Test
  @DisplayName("A new merchant order writes one parcel.created outbox event with MERCHANT_ORDER and the merchant id")
  void newOrderWritesOutboxEvent() throws Exception {
    String id = createOrder("merchant-events");

    List<Map<String, Object>> events = jdbc.queryForList(
        "SELECT event_type, payload::text AS payload FROM parcel.outbox_event WHERE aggregate_id = ?::uuid", id);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("event_type")).isEqualTo("parcel.created");
    JsonNode payload = objectMapper.readTree((String) events.get(0).get("payload")).get("payload");
    assertThat(payload.get("source").asText()).isEqualTo("MERCHANT_ORDER");
    assertThat(payload.get("merchantId").asText()).isEqualTo("merchant-events");
    assertThat((String) events.get(0).get("payload"))
        .doesNotContain("Test Customer", "Test Merchant Store", "771234567", "Temple Road", "ORD-IT-001");
  }

  @Test
  @DisplayName("A replay and a conflicting replay of a merchant order write no extra event")
  void replayWritesNoOutboxEvent() throws Exception {
    String id = createOrder("merchant-replay");

    mvc.perform(post(URL).with(merchant("merchant-replay"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk());
    mvc.perform(post(URL).with(merchant("merchant-replay"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("Test Customer", "Someone Else")))
        .andExpect(status().isConflict());

    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parcel.outbox_event WHERE aggregate_id = ?::uuid",
        Integer.class, id)).isEqualTo(1);
  }

  @Test
  @DisplayName("A status change on a merchant parcel publishes parcel.status.updated with the merchant id")
  void statusEventCarriesMerchantId() throws Exception {
    String id = createOrder("merchant-status");

    mvc.perform(patch("/api/v1/parcels/" + id + "/status")
            .with(jwt().jwt(j -> j.subject("dispatcher-it")).authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\": \"PICKED_UP\"}"))
        .andExpect(status().isOk());

    String json = jdbc.queryForObject("""
        SELECT payload::text FROM parcel.outbox_event
         WHERE aggregate_id = ?::uuid AND event_type = 'parcel.status.updated'
        """, String.class, id);
    JsonNode payload = objectMapper.readTree(json).get("payload");
    assertThat(payload.get("merchantId").asText()).isEqualTo("merchant-status");
    assertThat(payload.get("status").asText()).isEqualTo("PICKED_UP");
  }

  private String createOrder(String merchantId) throws Exception {
    String response = mvc.perform(post(URL).with(merchant(merchantId))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(response).get("id").asText();
  }

  @Test
  @DisplayName("Anonymous request without bearer token returns 401 Unauthorized")
  void anonymousReturns401() throws Exception {
    mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isUnauthorized());

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel", Integer.class);
    assertThat(count).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"CUSTOMER", "COURIER", "DISPATCHER", "ADMIN"})
  @DisplayName("Unauthorized roles return 403 Forbidden")
  void unauthorizedRolesReturn403(String role) throws Exception {
    mvc.perform(post(URL).with(jwt().jwt(j -> j.subject("user-other")).authorities(new SimpleGrantedAuthority("ROLE_" + role)))
            .contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isForbidden());

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel", Integer.class);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("GET /api/v1/merchant/orders lists only orders belonging to authenticated merchant")
  void listOrdersScopedToAuthenticatedMerchant() throws Exception {
    mvc.perform(post(URL).with(merchant("merchant-alpha"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("ORD-IT-001", "ORD-ALPHA-1")))
        .andExpect(status().isCreated());
    mvc.perform(post(URL).with(merchant("merchant-alpha"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("ORD-IT-001", "ORD-ALPHA-2")))
        .andExpect(status().isCreated());

    mvc.perform(post(URL).with(merchant("merchant-beta"))
            .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("ORD-IT-001", "ORD-BETA-1")))
        .andExpect(status().isCreated());

    mvc.perform(get(URL).with(merchant("merchant-alpha")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].merchantId").value("merchant-alpha"))
        .andExpect(jsonPath("$[1].merchantId").value("merchant-alpha"));

    mvc.perform(get(URL).with(merchant("merchant-beta")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].merchantId").value("merchant-beta"))
        .andExpect(jsonPath("$[0].externalOrderRef").value("ORD-BETA-1"));
  }

  @Test
  @DisplayName("Invalid order payload returns 422 with validation errors and stores nothing")
  void invalidPayloadReturns422() throws Exception {
    String invalidBody = """
        {
          "externalOrderRef": "",
          "sender": {"name": "", "phone": "123", "address": "short"},
          "recipient": {"name": "Bob", "phone": "0771234567", "address": "Valid Address 123"},
          "parcel": {"weightKg": -1.0, "category": ""}
        }
        """;

    mvc.perform(post(URL).with(merchant("merchant-val"))
            .contentType(MediaType.APPLICATION_JSON).content(invalidBody))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.fieldErrors").isMap())
        .andExpect(jsonPath("$.fieldErrors.externalOrderRef").exists());

    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel", Integer.class);
    assertThat(count).isZero();
    Integer outboxCount = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.outbox_event", Integer.class);
    assertThat(outboxCount).isZero();
  }

  @Test
  @DisplayName("If outbox event insertion fails, the parcel creation is rolled back atomically")
  void outboxFailureRollsBackOrder() throws Exception {
    jdbc.execute("""
        CREATE FUNCTION parcel.test_reject_merchant_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'outbox unavailable (test)'; END; $$
        """);
    jdbc.execute("""
        CREATE TRIGGER test_reject_merchant_outbox BEFORE INSERT ON parcel.outbox_event
        FOR EACH ROW EXECUTE FUNCTION parcel.test_reject_merchant_outbox()
        """);
    try {
      mvc.perform(post(URL).with(merchant("merchant-rollback"))
              .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("ORD-IT-001", "ORD-RB-001")))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.title").value("Order storage unavailable"));
    } finally {
      jdbc.execute("DROP TRIGGER test_reject_merchant_outbox ON parcel.outbox_event");
      jdbc.execute("DROP FUNCTION parcel.test_reject_merchant_outbox()");
    }

    Integer parcelCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM parcel.parcel WHERE external_order_ref = 'ORD-RB-001'", Integer.class);
    assertThat(parcelCount).isZero();
    Integer outboxCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM parcel.outbox_event WHERE event_type = 'parcel.created' AND payload::text LIKE '%ORD-RB-001%'",
        Integer.class);
    assertThat(outboxCount).isZero();
  }
}
