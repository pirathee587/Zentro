package com.zentro.trackingreporting.reporting;

import static com.zentro.trackingreporting.reporting.ExpectedDeliveryFacts.fact;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.consumer.TrackingKafkaConsumer;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
@Testcontainers
class ExpectedFactProjectionIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
    registry.add("zentro.kafka.enabled", () -> "false");
  }

  @MockBean JwtDecoder decoder;
  @Autowired JdbcTemplate jdbc;
  @Autowired TrackingService tracking;
  @Autowired ProcessedEventRepository processed;
  @Autowired DeliveryProjectionRepository projections;
  @Autowired ObjectMapper mapper;
  @Autowired PlatformTransactionManager transactions;
  private TrackingKafkaConsumer consumer;

  @BeforeEach void setup() {
    jdbc.execute("TRUNCATE tracking.tracking_parcel, tracking.tracking_event, tracking.processed_event, tracking.delivery_projection, tracking.delivery_daily");
    consumer = new TrackingKafkaConsumer(tracking, processed, mapper, projections);
  }

  @Test void normalFlowAndBothTerminalsMatchExpectedFacts() {
    verifyLedger(ExpectedDeliveryFacts.ledger());
  }

  @Test void duplicateEventIdsCountOnce() {
    var ledger = new ArrayList<>(ExpectedDeliveryFacts.ledger());
    ledger.addAll(ExpectedDeliveryFacts.ledger());
    verifyLedger(ledger);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tracking.tracking_event", Long.class)).isEqualTo(6);
  }

  @Test void terminalsArrivingBeforeEarlierStatusesMatchBusinessTimeFacts() {
    var ledger = new ArrayList<>(ExpectedDeliveryFacts.ledger());
    Collections.reverse(ledger);
    verifyLedger(ledger);
  }

  @Test void midnightBoundariesHaveExplicitExpectedDays() {
    var ledger = List.of(
        fact(11, 201, "CREATED", "2026-11-01T18:29:59Z"),
        fact(12, 202, "CREATED", "2026-11-01T18:30:00Z"),
        fact(13, 201, "DELIVERED", "2026-11-02T18:29:59Z"),
        fact(14, 202, "FAILED", "2026-11-02T18:30:00Z"));
    var expected = ExpectedDeliveryFacts.recount(ledger);
    assertThat(expected).containsExactly(
        java.util.Map.entry(LocalDate.of(2026, 11, 1), new ExpectedDeliveryFacts.Counts(1, 0, 0)),
        java.util.Map.entry(LocalDate.of(2026, 11, 2), new ExpectedDeliveryFacts.Counts(1, 1, 0)),
        java.util.Map.entry(LocalDate.of(2026, 11, 3), new ExpectedDeliveryFacts.Counts(0, 0, 1)));
    verifyLedger(ledger);
  }

  @Test void earlierTerminalMovesContributionAndLaterMilestoneCannotUndoIt() {
    verifyLedger(List.of(
        fact(21, 301, "DELIVERED", "2026-11-03T00:00:00Z"),
        fact(22, 301, "CREATED", "2026-11-01T00:00:00Z"),
        fact(23, 301, "FAILED", "2026-11-02T00:00:00Z"),
        fact(24, 301, "OUT_FOR_DELIVERY", "2026-11-04T00:00:00Z")));
    assertThat(jdbc.queryForObject("SELECT current_status FROM tracking.delivery_projection", String.class))
        .isEqualTo("FAILED");
  }

  @Test void fullHistoryRecountMatchesIncrementalCountsAndExpectedLedger() throws Exception {
    verifyLedger(ExpectedDeliveryFacts.ledger());
    var incremental = jdbc.queryForList("SELECT * FROM tracking.delivery_daily_metrics ORDER BY day");
    try (var resource = getClass().getResourceAsStream("/sql/delivery-recount.sql")) {
      assertThat(resource).isNotNull();
      var sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(jdbc.queryForList(sql)).isEqualTo(incremental);
    }
  }

  private void verifyLedger(List<ExpectedDeliveryFacts.Fact> ledger) {
    var applied = new ArrayList<ExpectedDeliveryFacts.Fact>();
    for (var fact : ledger) {
      apply(fact);
      applied.add(fact);
      var actual = new TreeMap<LocalDate, ExpectedDeliveryFacts.Counts>();
      jdbc.query("SELECT day, created, delivered, failed FROM tracking.delivery_daily ORDER BY day", row -> {
        actual.put(row.getObject("day", LocalDate.class), new ExpectedDeliveryFacts.Counts(
            row.getLong("created"), row.getLong("delivered"), row.getLong("failed")));
      });
      assertThat(actual).as("incremental counts after event %s", fact.eventId())
          .isEqualTo(ExpectedDeliveryFacts.recount(applied));
    }
  }

  private void apply(ExpectedDeliveryFacts.Fact fact) {
    String topic = fact.status().equals("CREATED") ? "parcel.created" : "parcel.status.updated";
    var envelope = mapper.createObjectNode().put("eventId", fact.eventId().toString())
        .put("aggregateId", fact.parcelId().toString()).put("eventType", topic)
        .put("occurredAt", fact.occurredAt().toString());
    var payload = envelope.putObject("payload").put("trackingRef", "ZT-7K3QX9M2PA")
        .put("status", fact.status()).put("occurredAt", fact.occurredAt().toString());
    if (fact.status().equals("CREATED")) payload.put("source", "CUSTOMER_BOOKING");
    else payload.put("previousStatus", "OUT_FOR_DELIVERY").put("actorRole", "DISPATCHER");
    if (fact.status().equals("FAILED")) payload.put("failureReason", "Synthetic failure");
    new TransactionTemplate(transactions).executeWithoutResult(transaction ->
        consumer.onParcelEvent(envelope.toString(), topic, null));
  }
}
