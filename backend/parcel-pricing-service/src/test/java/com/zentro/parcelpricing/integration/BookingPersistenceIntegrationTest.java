package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What a booking leaves in the database: the parcel row exactly as stored, the single parcel.created outbox
 * event (checked against the shared schema file) and unique tracking references over many bookings.
 * Never touches the shared database; the outbox publisher stays disabled. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingPersistenceIntegrationTest {
  private static final String TRACKING_REF = "^ZT-[0-9A-HJKMNP-TV-Z]{10}$";
  private static final String BODY = """
      {
        "sender": {"name": "  Nimal Perera  ", "phone": "077 123-4567", "address": "  12 Galle Road, Colombo 03  ", "city": " Colombo "},
        "recipient": {"name": "Kamala Silva", "phone": "+94 71-234 5678", "address": "45 Temple Street, Kandy", "city": "Kandy"},
        "parcel": {"weightKg": 2.555, "category": "  Documents  ", "size": "SMALL", "description": "  Books  "},
        "destinationType": "OTHER_DISTRICT",
        "isExpress": true,
        "notes": "  Call before delivery  ",
        "fee": 1, "status": "DELIVERED", "trackingRef": "ZT-FORGED0000", "customerSubject": "someone-else"
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

  private static RequestPostProcessor customer(String subject) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }

  private JsonNode book(String subject, String body, String idempotencyKey) throws Exception {
    var request = post("/api/v1/parcels").with(customer(subject)).contentType(MediaType.APPLICATION_JSON).content(body);
    if (idempotencyKey != null) {
      request.header("Idempotency-Key", idempotencyKey);
    }
    return objectMapper.readTree(mvc.perform(request).andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString());
  }

  @Test
  void invalidRequestsLeaveNoParcelIdempotencyOrOutboxRows() throws Exception {
    long parcels = jdbc.queryForObject("SELECT count(*) FROM parcel.parcel", Long.class);
    long events = jdbc.queryForObject("SELECT count(*) FROM parcel.outbox_event", Long.class);
    for (String weight : List.of("0", "0.09", "20.01", "1.12345")) {
      mvc.perform(post("/api/v1/parcels").with(customer("synthetic-invalid-124"))
          .header("Idempotency-Key", "synthetic-invalid-124-" + weight)
          .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("2.555", weight)))
          .andExpect(status().isUnprocessableEntity());
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.parcel", Long.class)).isEqualTo(parcels);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.outbox_event", Long.class)).isEqualTo(events);
    // Idempotency is stored on the parcel row, rather than in a separate table.
    assertThat(jdbc.queryForObject("SELECT count(*) FROM parcel.parcel WHERE customer_subject = 'synthetic-invalid-124'", Long.class)).isZero();
  }

  @Test
  void exactWeightBoundariesAreStoredWithoutRoundingLoss() throws Exception {
    for (String weight : List.of("0.1", "20")) {
      JsonNode response = book("synthetic-boundary-124", BODY.replace("2.555", weight), "synthetic-boundary-" + weight);
      BigDecimal stored = jdbc.queryForObject("SELECT weight_kg FROM parcel.parcel WHERE id = ?::uuid", BigDecimal.class, response.get("id").asText());
      assertThat(stored).isEqualByComparingTo(weight);
    }
  }

  @Test
  @DisplayName("The stored row has status CREATED, version 0, the server-side fee, the token owner and normalised fields")
  void storedRowIsComplete() throws Exception {
    JsonNode booking = book("persist-customer-1", BODY, "persist-key-1");

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM parcel.parcel WHERE id = ?::uuid",
        booking.get("id").asText());
    // 550 base + 1.555 extra kg x 120 (186.60) + 402 other district + 300 express, priced as entered.
    assertThat((BigDecimal) row.get("fee")).isEqualByComparingTo("1438.60");
    assertThat(booking.get("fee").decimalValue()).isEqualByComparingTo("1438.60");
    assertThat(row.get("status")).isEqualTo("CREATED");
    assertThat(row.get("version")).isEqualTo(0L);
    assertThat(row.get("currency")).isEqualTo("LKR");
    assertThat(row.get("estimated_days")).isEqualTo(1);
    assertThat(row.get("is_express")).isEqualTo(true);
    assertThat(row.get("destination_type")).isEqualTo("OTHER_DISTRICT");
    assertThat(row.get("customer_subject")).isEqualTo("persist-customer-1");
    assertThat(row.get("tracking_ref")).isEqualTo(booking.get("trackingRef").asText()).isNotEqualTo("ZT-FORGED0000");
    assertThat((String) row.get("tracking_ref")).matches(TRACKING_REF);
    assertThat(row.get("sender_name")).isEqualTo("Nimal Perera");
    assertThat(row.get("sender_phone")).isEqualTo("0771234567");
    assertThat(row.get("sender_address")).isEqualTo("12 Galle Road, Colombo 03");
    assertThat(row.get("sender_city")).isEqualTo("Colombo");
    assertThat(row.get("recipient_phone")).isEqualTo("+94712345678");
    assertThat((BigDecimal) row.get("weight_kg")).isEqualByComparingTo("2.56");
    assertThat(row.get("category")).isEqualTo("Documents");
    assertThat(row.get("description")).isEqualTo("Books");
    assertThat(row.get("notes")).isEqualTo("Call before delivery");
    assertThat(row.get("size")).isEqualTo("SMALL");
    assertThat(row.get("idempotency_key")).isEqualTo("persist-key-1");
    assertThat((String) row.get("request_hash")).hasSize(64);
    assertThat(row.get("created_at")).isNotNull();
    assertThat(booking.get("status").asText()).isEqualTo("CREATED");
  }

  @Test
  @DisplayName("Exactly one parcel.created event is stored, matches the schema file and carries no personal data")
  void oneSchemaValidCreatedEvent() throws Exception {
    JsonNode booking = book("persist-customer-2", BODY, "persist-key-2");
    String parcelId = booking.get("id").asText();

    List<Map<String, Object>> events = jdbc.queryForList("""
        SELECT event_id, event_type, payload::text AS payload, occurred_at, published_at, attempts
          FROM parcel.outbox_event WHERE aggregate_id = ?::uuid
        """, parcelId);
    assertThat(events).hasSize(1);
    Map<String, Object> event = events.get(0);
    assertThat(event.get("event_type")).isEqualTo("parcel.created");
    assertThat(event.get("published_at")).isNull();
    assertThat(event.get("attempts")).isEqualTo(0);

    String payload = (String) event.get("payload");
    JsonNode envelope = objectMapper.readTree(payload);
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    Path contracts = Path.of("..", "..", "contracts", "events").toAbsolutePath().normalize();
    JsonSchema schema = factory.getSchema(
        SchemaLocation.of(contracts.resolve("parcel-created.schema.json").toUri().toString()), config);
    assertThat(schema.validate(envelope)).isEmpty();

    assertThat(envelope.get("eventId").asText()).isEqualTo(event.get("event_id").toString());
    assertThat(envelope.get("aggregateId").asText()).isEqualTo(parcelId);
    assertThat(envelope.get("payload").get("trackingRef").asText()).isEqualTo(booking.get("trackingRef").asText());
    assertThat(envelope.get("payload").get("status").asText()).isEqualTo("CREATED");
    assertThat(envelope.get("payload").get("source").asText()).isEqualTo("CUSTOMER_BOOKING");
    OffsetDateTime createdAt = jdbc.queryForObject("SELECT created_at FROM parcel.parcel WHERE id = ?::uuid",
        OffsetDateTime.class, parcelId);
    assertThat(OffsetDateTime.parse(envelope.get("occurredAt").asText()).toInstant()).isEqualTo(createdAt.toInstant());
    assertThat(payload).doesNotContain("Nimal", "Kamala", "0771234567", "712345678", "Galle Road", "Temple Street",
        "Call before delivery", "Books", "persist-customer-2", "persist-key-2", "1438");
  }

  @Test
  @DisplayName("A new booking writes no status history row; history starts with the first status change")
  void bookingWritesNoInitialHistoryRow() throws Exception {
    JsonNode booking = book("persist-customer-3", BODY, null);

    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM parcel.parcel_status_history WHERE parcel_id = ?::uuid", Integer.class,
        booking.get("id").asText())).isZero();
  }

  @Test
  @DisplayName("200 bookings get 200 different tracking references in the ZT- format, each with one created event")
  void manyBookingsGetUniqueReferences() throws Exception {
    String body = """
        {
          "sender": {"name": "Bulk Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
          "recipient": {"name": "Bulk Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
          "parcel": {"weightKg": 1.0, "category": "Box", "size": "SMALL"}
        }
        """;
    Set<String> references = new HashSet<>();
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      JsonNode booking = book("bulk-customer", body, null);
      references.add(booking.get("trackingRef").asText());
      ids.add(booking.get("id").asText());
    }

    assertThat(references).hasSize(200).allMatch(ref -> ref.matches(TRACKING_REF));
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(DISTINCT tracking_ref) FROM parcel.parcel WHERE customer_subject = 'bulk-customer'",
        Integer.class)).isEqualTo(200);
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.outbox_event e JOIN parcel.parcel p ON p.id = e.aggregate_id
         WHERE p.customer_subject = 'bulk-customer' AND e.event_type = 'parcel.created'
        """, Integer.class)).isEqualTo(200);
  }
}
