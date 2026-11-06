package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Duplicate submissions of POST /api/v1/parcels through the full application and a real database:
 * replays, conflicting reuse of a key, truly parallel requests and keys that belong to different customers.
 * Never touches the shared database. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingDuplicateSubmissionIntegrationTest {
  private static final String BODY = """
      {
        "sender": {"name": "Dup Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Dup Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
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

  private MockHttpServletResponse book(String subject, String key, String body) throws Exception {
    var request = post("/api/v1/parcels")
        .with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType(MediaType.APPLICATION_JSON).content(body);
    if (key != null) {
      request.header("Idempotency-Key", key);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private JsonNode json(MockHttpServletResponse response) throws Exception {
    return objectMapper.readTree(response.getContentAsString());
  }

  private int parcelRows(String subject) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel WHERE customer_subject = ?", Integer.class, subject);
  }

  private int createdEvents(String subject) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.outbox_event e JOIN parcel.parcel p ON p.id = e.aggregate_id
         WHERE p.customer_subject = ? AND e.event_type = 'parcel.created'
        """, Integer.class, subject);
  }

  @Test
  @DisplayName("The same key and body twice returns 201 then 200 with the same booking, one row and one event")
  void replayReturnsTheOriginalBooking() throws Exception {
    String key = UUID.randomUUID().toString();

    MockHttpServletResponse first = book("dup-a", key, BODY);
    MockHttpServletResponse second = book("dup-a", key, BODY);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(second.getStatus()).isEqualTo(200);
    assertThat(second.getHeader("Location")).isNull();
    assertThat(json(second).get("id")).isEqualTo(json(first).get("id"));
    assertThat(json(second).get("trackingRef")).isEqualTo(json(first).get("trackingRef"));
    assertThat(json(second).get("fee")).isEqualTo(json(first).get("fee"));
    assertThat(parcelRows("dup-a")).isEqualTo(1);
    assertThat(createdEvents("dup-a")).isEqualTo(1);
  }

  @Test
  @DisplayName("Reusing a key with a different body returns 409 and stores nothing new")
  void sameKeyDifferentBodyConflicts() throws Exception {
    String key = UUID.randomUUID().toString();
    assertThat(book("dup-b", key, BODY).getStatus()).isEqualTo(201);

    MockHttpServletResponse conflict = book("dup-b", key, BODY.replace("2.0", "3.0"));

    assertThat(conflict.getStatus()).isEqualTo(409);
    assertThat(conflict.getContentType()).startsWith("application/problem+json");
    assertThat(conflict.getContentAsString()).doesNotContain("Dup Sender", "0771234567");
    assertThat(parcelRows("dup-b")).isEqualTo(1);
    assertThat(createdEvents("dup-b")).isEqualTo(1);
  }

  @Test
  void sameKeyDifferentPriceAffectingPrecisionReturns409() throws Exception {
    String subject = "precision-" + UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    MockHttpServletResponse first = book(subject, key, BODY.replace("2.0", "2.551"));
    assertThat(first.getStatus()).isEqualTo(201);
    UUID parcelId = UUID.fromString(json(first).get("id").asText());
    var original = jdbc.queryForMap("SELECT * FROM parcel.parcel WHERE id = ?", parcelId);
    var originalEvent = jdbc.queryForMap(
        "SELECT * FROM parcel.outbox_event WHERE aggregate_id = ? AND event_type = 'parcel.created'", parcelId);

    MockHttpServletResponse second = book(subject, key, BODY.replace("2.0", "2.554"));

    // Check persistence before the regression assertion, so the failing run still verifies no overwrite.
    assertThat(parcelRows(subject)).isEqualTo(1);
    assertThat(createdEvents(subject)).isEqualTo(1);
    assertThat(jdbc.queryForMap("SELECT * FROM parcel.parcel WHERE id = ?", parcelId)).isEqualTo(original);
    assertThat(jdbc.queryForMap(
        "SELECT * FROM parcel.outbox_event WHERE aggregate_id = ? AND event_type = 'parcel.created'", parcelId))
        .isEqualTo(originalEvent);
    assertThat(second.getStatus()).as("changed price-affecting precision must conflict rather than replay")
        .isEqualTo(409);
  }

  @Test
  @DisplayName("Five parallel requests with one key create exactly one booking and one event")
  void parallelRequestsCreateOneBooking() throws Exception {
    String key = UUID.randomUUID().toString();
    int threads = 5;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<MockHttpServletResponse>> results = new ArrayList<>();
    try {
      for (int i = 0; i < threads; i++) {
        Callable<MockHttpServletResponse> call = () -> {
          start.await();
          return book("dup-c", key, BODY);
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      List<String> references = new ArrayList<>();
      for (Future<MockHttpServletResponse> result : results) {
        MockHttpServletResponse response = result.get();
        statuses.add(response.getStatus());
        references.add(json(response).get("trackingRef").asText());
      }

      assertThat(statuses).containsOnly(201, 200);
      assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
      assertThat(references).containsOnly(references.get(0));
    } finally {
      pool.shutdownNow();
    }
    assertThat(parcelRows("dup-c")).isEqualTo(1);
    assertThat(createdEvents("dup-c")).isEqualTo(1);
  }

  @Test
  void numericEquivalenceReplaysWithoutRewritingV2Fingerprint() throws Exception {
    String subject = "equivalent-" + UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    assertThat(book(subject, key, BODY.replace("2.0", "2.550")).getStatus()).isEqualTo(201);
    var original = jdbc.queryForMap("SELECT * FROM parcel.parcel WHERE customer_subject = ?", subject);
    assertThat(((Number) original.get("fingerprint_version")).intValue()).isEqualTo(2);
    assertThat(book(subject, key, BODY.replace("2.0", "2.55")).getStatus()).isEqualTo(200);
    assertThat(jdbc.queryForMap("SELECT * FROM parcel.parcel WHERE customer_subject = ?", subject)).isEqualTo(original);
    assertThat(createdEvents(subject)).isEqualTo(1);
  }

  @Test
  void migrationPreservesHistoricalFingerprintAndDefaultsItsVersionToV1() {
    try (PostgreSQLContainer<?> historical = new PostgreSQLContainer<>("postgres:16-alpine")) {
      historical.start();
      var flyway = org.flywaydb.core.Flyway.configure()
          .dataSource(historical.getJdbcUrl(), historical.getUsername(), historical.getPassword())
          .target("7").load();
      flyway.migrate();
      var database = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
          historical.getJdbcUrl(), historical.getUsername(), historical.getPassword()));
      String hash = "a".repeat(64);
      database.update("""
          INSERT INTO parcel.parcel (tracking_ref, customer_subject, sender_name, sender_phone, sender_address,
            recipient_name, recipient_phone, recipient_address, weight_kg, category, fee, currency,
            estimated_days, idempotency_key, request_hash)
          VALUES ('ZT-UPGRADE123', 'historical', 'Sender', '0771234567', 'Colombo',
            'Recipient', '0712345678', 'Kandy', 2.55, 'Small Box', 1072, 'LKR', 3, 'old-key', ?)
          """, hash);
      var original = database.queryForMap("SELECT * FROM parcel.parcel WHERE customer_subject = 'historical'");
      org.flywaydb.core.Flyway.configure()
          .dataSource(historical.getJdbcUrl(), historical.getUsername(), historical.getPassword()).load().migrate();
      var upgraded = database.queryForMap("SELECT * FROM parcel.parcel WHERE customer_subject = 'historical'");
      assertThat(((Number) upgraded.remove("fingerprint_version")).intValue()).isEqualTo(1);
      assertThat(upgraded).isEqualTo(original);
    }
  }

  @Test
  void historicalV1FingerprintReplaysAndRejectsChangedContent() throws Exception {
    String subject = "legacy-" + UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    // Frozen pre-v2 serialization, independent of the current fingerprint implementation.
    String canonical = """
        {"destinationType":"OTHER_DISTRICT","isExpress":false,"notes":null,"parcel":{"category":"Small Box","description":null,"size":"SMALL","weightKg":2.55},"recipient":{"address":"45 Temple Street, Kandy","city":null,"name":"Dup Recipient","phone":"0712345678"},"sender":{"address":"12 Galle Road, Colombo 03","city":null,"name":"Dup Sender","phone":"0771234567"}}
        """.strip();
    String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
        .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    jdbc.update("""
        INSERT INTO parcel.parcel (tracking_ref, customer_subject, sender_name, sender_phone, sender_address,
          recipient_name, recipient_phone, recipient_address, weight_kg, size, category, destination_type,
          fee, currency, estimated_days, idempotency_key, request_hash)
        VALUES (?, ?, 'Dup Sender', '0771234567', '12 Galle Road, Colombo 03',
          'Dup Recipient', '0712345678', '45 Temple Street, Kandy', 2.55, 'SMALL', 'Small Box',
          'OTHER_DISTRICT', 1072.00, 'LKR', 3, ?, ?)
        """, "ZT-LEGACY1234", subject, key, hash);
    var original = jdbc.queryForMap("SELECT * FROM parcel.parcel WHERE customer_subject = ?", subject);
    assertThat(((Number) original.get("fingerprint_version")).intValue()).isEqualTo(1);
    assertThat(book(subject, key, BODY.replace("2.0", "2.554")).getStatus()).isEqualTo(200);
    assertThat(book(subject, key, BODY.replace("2.0", "3.0")).getStatus()).isEqualTo(409);
    assertThat(jdbc.queryForMap("SELECT * FROM parcel.parcel WHERE customer_subject = ?", subject)).isEqualTo(original);
    assertThat(createdEvents(subject)).isZero();
  }

  @Test
  void concurrentPrecisionConflictCreatesOnlyWinningBooking() throws Exception {
    String subject = "conflicting-" + UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<MockHttpServletResponse> first = pool.submit(() -> {
        start.await();
        return book(subject, key, BODY.replace("2.0", "2.551"));
      });
      Future<MockHttpServletResponse> second = pool.submit(() -> {
        start.await();
        return book(subject, key, BODY.replace("2.0", "2.554"));
      });
      start.countDown();
      var a = first.get(30, java.util.concurrent.TimeUnit.SECONDS);
      var b = second.get(30, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(List.of(a.getStatus(), b.getStatus())).containsExactlyInAnyOrder(201, 409);
      String winnerWeight = a.getStatus() == 201 ? "2.551" : "2.554";
      assertThat(book(subject, key, BODY.replace("2.0", winnerWeight)).getStatus()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
    assertThat(parcelRows(subject)).isEqualTo(1);
    assertThat(createdEvents(subject)).isEqualTo(1);
  }

  @Test
  void differentKeysKeepPrecisionDistinctBookingsIndependent() throws Exception {
    String subject = "distinct-" + UUID.randomUUID();
    var first = book(subject, "first", BODY.replace("2.0", "2.551"));
    var second = book(subject, "second", BODY.replace("2.0", "2.554"));
    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(second.getStatus()).isEqualTo(201);
    assertThat(json(first).get("id")).isNotEqualTo(json(second).get("id"));
    assertThat(jdbc.queryForList("SELECT request_hash FROM parcel.parcel WHERE customer_subject = ?", String.class, subject))
        .doesNotHaveDuplicates().hasSize(2);
    assertThat(parcelRows(subject)).isEqualTo(2);
    assertThat(createdEvents(subject)).isEqualTo(2);
  }

  @Test
  @DisplayName("The same key used by two different customers creates two separate bookings")
  void keyIsScopedToTheCustomer() throws Exception {
    String key = UUID.randomUUID().toString();

    MockHttpServletResponse first = book("dup-d1", key, BODY);
    MockHttpServletResponse second = book("dup-d2", key, BODY);

    assertThat(first.getStatus()).isEqualTo(201);
    assertThat(second.getStatus()).isEqualTo(201);
    assertThat(json(second).get("trackingRef")).isNotEqualTo(json(first).get("trackingRef"));
    assertThat(parcelRows("dup-d1")).isEqualTo(1);
    assertThat(parcelRows("dup-d2")).isEqualTo(1);
  }

  @Test
  @DisplayName("Without a key, two identical submissions are two bookings")
  void noKeyMeansNoDeduplication() throws Exception {
    assertThat(book("dup-e", null, BODY).getStatus()).isEqualTo(201);
    assertThat(book("dup-e", null, BODY).getStatus()).isEqualTo(201);

    assertThat(parcelRows("dup-e")).isEqualTo(2);
    assertThat(createdEvents("dup-e")).isEqualTo(2);
  }
}
