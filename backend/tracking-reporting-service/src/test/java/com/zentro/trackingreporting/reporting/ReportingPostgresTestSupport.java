package com.zentro.trackingreporting.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.trackingreporting.messaging.consumer.TrackingKafkaConsumer;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import org.junit.jupiter.api.BeforeEach;
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
abstract class ReportingPostgresTestSupport {
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

  void apply(ExpectedDeliveryFacts.Fact fact) {
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
