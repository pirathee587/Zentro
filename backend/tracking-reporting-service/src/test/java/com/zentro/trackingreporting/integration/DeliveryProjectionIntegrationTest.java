package com.zentro.trackingreporting.integration;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.consumer.TrackingKafkaConsumer;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.reporting.DeliveryProjectionRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeliveryProjectionIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", postgres::getJdbcUrl);
    r.add("spring.datasource.username", postgres::getUsername);
    r.add("spring.datasource.password", postgres::getPassword);
    r.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    r.add("zentro.security.audience", () -> "zentro-api");
    r.add("zentro.kafka.enabled", () -> "false");
  }
  @MockBean JwtDecoder decoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired TrackingService tracking;
  @Autowired ProcessedEventRepository processed;
  @Autowired DeliveryProjectionRepository projections;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager transactions;
  TrackingKafkaConsumer consumer;

  @BeforeEach void setup() {
    jdbc.execute("TRUNCATE tracking.tracking_event, tracking.processed_event, tracking.delivery_projection, tracking.delivery_daily");
    consumer = new TrackingKafkaConsumer(tracking, processed, mapper, projections);
  }
  record Fact(String topic, String json) {}
  Fact fact(UUID id, UUID parcel, String status, String time) {
    String type = status.equals("CREATED") ? "parcel.created" : "parcel.status.updated";
    var e = mapper.createObjectNode().put("eventId", id.toString()).put("aggregateId", parcel.toString())
        .put("eventType", type).put("occurredAt", time);
    var p = e.putObject("payload").put("trackingRef", "ZT-7K3QX9M2PA").put("status", status).put("occurredAt", time);
    if (status.equals("CREATED")) p.put("source", "CUSTOMER_BOOKING");
    else p.put("previousStatus", "OUT_FOR_DELIVERY").put("actorRole", "DISPATCHER");
    if (status.equals("FAILED")) p.put("failureReason", "Synthetic failure");
    return new Fact(type, e.toString());
  }
  void apply(Fact f) { new TransactionTemplate(transactions).executeWithoutResult(s -> consumer.onParcelEvent(f.json(), f.topic(), null)); }
  List<Map<String,Object>> metrics() { return jdbc.queryForList("SELECT * FROM tracking.delivery_daily_metrics ORDER BY day"); }
  void recountMatches() throws Exception {
    String sql = new String(getClass().getResourceAsStream("/sql/delivery-recount.sql").readAllBytes(), StandardCharsets.UTF_8);
    assertThat(metrics()).isEqualTo(jdbc.queryForList(sql));
  }

  @Test void midnightLateFactsDuplicatesAndReplayEqualIndependentRecount() throws Exception {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    List<Fact> facts = List.of(
        fact(UUID.randomUUID(), a, "CREATED", "2026-11-01T18:29:59Z"), // Colombo Nov 1
        fact(UUID.randomUUID(), b, "CREATED", "2026-11-01T18:30:00Z"), // Colombo Nov 2
        fact(UUID.randomUUID(), a, "DELIVERED", "2026-11-02T18:30:00Z"), // Colombo Nov 3
        fact(UUID.randomUUID(), a, "OUT_FOR_DELIVERY", "2026-11-02T10:00:00Z"),
        fact(UUID.randomUUID(), b, "FAILED", "2026-11-04T10:00:00Z"),
        fact(UUID.randomUUID(), c, "CREATED", "2026-11-07T00:00:00Z"));
    for (Fact f : facts) { apply(f); apply(f); }
    recountMatches();
    var rows = metrics();
    assertThat(rows).hasSize(7);
    assertThat(rows.get(0)).containsEntry("created", 1L).containsEntry("active", 1L).containsEntry("success_rate", null);
    assertThat(rows.get(1)).containsEntry("created", 1L).containsEntry("active", 2L).containsEntry("success_rate", null);
    assertThat(rows.get(2)).containsEntry("delivered", 1L).containsEntry("active", 1L);
    assertThat(rows.get(3)).containsEntry("failed", 1L).containsEntry("active", 0L);
    assertThat(rows.get(4)).containsEntry("created", 0L).containsEntry("delivered", 0L).containsEntry("failed", 0L)
        .containsEntry("active", 0L).containsEntry("success_rate", null);
    assertThat(rows.get(6)).containsEntry("active", 1L).containsEntry("success_rate", null);
    assertThat((java.math.BigDecimal)rows.get(3).get("success_rate")).isEqualByComparingTo("0");
    assertThat(jdbc.queryForObject("SELECT current_status FROM tracking.delivery_projection WHERE parcel_id = ?", String.class, a)).isEqualTo("DELIVERED");
    jdbc.execute("TRUNCATE tracking.tracking_event, tracking.processed_event, tracking.delivery_projection, tracking.delivery_daily");
    var reversed = new ArrayList<>(facts); Collections.reverse(reversed);
    reversed.forEach(this::apply);
    recountMatches();
    assertThat(metrics()).isEqualTo(rows);
  }

  @Test void concurrentClaimsAndRollbackAfterEffectProduceExactlyOneCommittedFact() throws Exception {
    UUID id = UUID.randomUUID(), parcel = UUID.randomUUID();
    Fact f = fact(id, parcel, "CREATED", "2026-11-01T10:00:00Z");
    assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
      consumer.onParcelEvent(f.json(), f.topic(), null);
      throw new IllegalStateException("synthetic crash after aggregate update");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking.processed_event", Long.class)).isZero();
    assertThat(metrics()).isEmpty();
    try (var executor = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i=0; i<12; i++) tasks.add(() -> { apply(f); return null; });
      for (var result : executor.invokeAll(tasks)) result.get(30, TimeUnit.SECONDS);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking.tracking_event", Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking.processed_event", Long.class)).isEqualTo(1);
    assertThat(metrics().get(0)).containsEntry("created", 1L);
    recountMatches();
  }

  @Test void earlierTerminalArrivingLastMovesContributionWithoutDoubleCounting() throws Exception {
    UUID parcel = UUID.randomUUID();
    apply(fact(UUID.randomUUID(), parcel, "CREATED", "2026-11-01T00:00:00Z"));
    apply(fact(UUID.randomUUID(), parcel, "DELIVERED", "2026-11-03T00:00:00Z"));
    apply(fact(UUID.randomUUID(), parcel, "FAILED", "2026-11-02T00:00:00Z"));
    recountMatches();
    assertThat(metrics().stream().mapToLong(r -> (Long)r.get("delivered")).sum()).isZero();
    assertThat(metrics().stream().mapToLong(r -> (Long)r.get("failed")).sum()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking.tracking_event", Long.class)).isEqualTo(3);
  }

  @Test void sameTimeTerminalTieIsIndependentOfArrivalOrder() throws Exception {
    UUID parcel = UUID.randomUUID();
    apply(fact(UUID.fromString("00000000-0000-0000-0000-000000000002"), parcel, "DELIVERED", "2026-11-01T00:00:00Z"));
    apply(fact(UUID.fromString("00000000-0000-0000-0000-000000000001"), parcel, "FAILED", "2026-11-01T00:00:00Z"));
    assertThat(metrics().get(0)).containsEntry("failed", 1L).containsEntry("delivered", 0L).containsEntry("active", 0L);
    recountMatches();
  }

  @Test void differentParcelsUpdatingSameDayDoNotLoseIncrements() throws Exception {
    try (var executor = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Void>>();
      for (int i = 0; i < 12; i++) {
        Fact f = fact(UUID.randomUUID(), UUID.randomUUID(), "CREATED", "2026-11-01T10:00:00Z");
        tasks.add(() -> { apply(f); return null; });
      }
      for (var result : executor.invokeAll(tasks)) result.get(30, TimeUnit.SECONDS);
    }
    assertThat(metrics().get(0)).containsEntry("created", 12L).containsEntry("active", 12L);
    recountMatches();
  }
}
