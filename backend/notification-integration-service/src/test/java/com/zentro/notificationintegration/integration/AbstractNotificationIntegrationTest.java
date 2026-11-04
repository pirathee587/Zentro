package com.zentro.notificationintegration.integration;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared setup: a throwaway PostgreSQL and Kafka (never the shared database), the consumer enabled, short retry
 * delays, and both parcel topics created before the listener starts. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext // stop the listener and retry scheduler with the class, before its containers go away
abstract class AbstractNotificationIntegrationTest {
  static final String STATUS_TOPIC = "parcel.status.updated";
  static final String CREATED_TOPIC = "parcel.created";
  private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.kafka.consumer.properties[metadata.max.age.ms]", () -> "1000");
    registry.add("zentro.notifications.consumer.enabled", () -> "true");
    registry.add("zentro.notifications.retry.poll-interval-ms", () -> "200");
    registry.add("zentro.notifications.retry.base-delay-ms", () -> "200");
    registry.add("zentro.notifications.retry.max-delay-ms", () -> "1000");
  }

  @TestConfiguration
  static class Topics {
    @Bean
    NewTopic parcelCreatedTopic() {
      return TopicBuilder.name(CREATED_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic parcelStatusUpdatedTopic() {
      return TopicBuilder.name(STATUS_TOPIC).partitions(3).replicas(1).build();
    }
  }

  @Autowired KafkaTemplate<String, String> kafka;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;
  @MockBean JwtDecoder jwtDecoder;

  static String newTrackingRef() {
    StringBuilder ref = new StringBuilder("ZT-");
    for (int i = 0; i < 10; i++) {
      ref.append(CROCKFORD[RANDOM.nextInt(CROCKFORD.length)]);
    }
    return ref.toString();
  }

  String statusEvent(UUID eventId, UUID parcelId, String trackingRef, String status, String failureReason) {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", eventId.toString())
        .put("eventType", STATUS_TOPIC)
        .put("occurredAt", "2026-11-05T10:15:30.123456Z")
        .put("aggregateId", parcelId.toString());
    ObjectNode payload = envelope.putObject("payload")
        .put("trackingRef", trackingRef)
        .put("previousStatus", "CREATED")
        .put("status", status)
        .put("actorRole", "DISPATCHER")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z");
    if (failureReason != null) {
      payload.put("failureReason", failureReason);
    }
    return envelope.toString();
  }

  String createdEvent(UUID eventId, UUID parcelId, String trackingRef) {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", eventId.toString())
        .put("eventType", CREATED_TOPIC)
        .put("occurredAt", "2026-11-05T10:15:00Z")
        .put("aggregateId", parcelId.toString());
    envelope.putObject("payload")
        .put("trackingRef", trackingRef)
        .put("status", "CREATED")
        .put("source", "CUSTOMER_BOOKING")
        .put("occurredAt", "2026-11-05T10:15:00Z");
    return envelope.toString();
  }

  void send(String topic, UUID parcelId, String value) throws Exception {
    kafka.send(topic, parcelId.toString(), value).get();
  }

  List<Map<String, Object>> rowsFor(UUID eventId) {
    return jdbc.queryForList("""
        SELECT status, parcel_status, message, attempts, last_error, next_attempt_at, tracking_ref
          FROM notification.notification_attempt WHERE event_id = ?
        """, eventId);
  }

  Map<String, Object> awaitRow(UUID eventId, String status) {
    await().atMost(Duration.ofSeconds(60)).until(() -> rowsFor(eventId).stream()
        .anyMatch(r -> status.equals(r.get("status"))));
    return rowsFor(eventId).get(0);
  }
}
