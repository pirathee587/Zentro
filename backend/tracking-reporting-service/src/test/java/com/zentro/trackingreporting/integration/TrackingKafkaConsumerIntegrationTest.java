package com.zentro.trackingreporting.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zentro.trackingreporting.messaging.idempotency.ProcessedEventRepository;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext
class TrackingKafkaConsumerIntegrationTest {

  private static final String STATUS_TOPIC = "parcel.status.updated";
  private static final String CREATED_TOPIC = "parcel.created";
  private static final Duration WAIT = Duration.ofSeconds(30);
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
    registry.add("spring.kafka.consumer.group-id", () -> "test-tracking-" + UUID.randomUUID());
    registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    registry.add("spring.kafka.consumer.properties[metadata.max.age.ms]", () -> "1000");
    registry.add("zentro.kafka.enabled", () -> "true");
    registry.add("zentro.kafka.consumer.retry.interval-ms", () -> "200");
    registry.add("zentro.kafka.consumer.retry.max-attempts", () -> "3");
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
  @Autowired TrackingEventRepository trackingEventRepository;
  @Autowired ProcessedEventRepository processedEventRepository;
  @MockBean JwtDecoder jwtDecoder;

  private static String newTrackingRef() {
    StringBuilder ref = new StringBuilder("ZT-");
    for (int i = 0; i < 10; i++) {
      ref.append(CROCKFORD[RANDOM.nextInt(CROCKFORD.length)]);
    }
    return ref.toString();
  }

  private String statusEventJson(UUID eventId, UUID parcelId, String trackingRef, String status, String prevStatus) {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", eventId.toString())
        .put("eventType", STATUS_TOPIC)
        .put("occurredAt", "2026-11-05T10:15:30.123456Z")
        .put("aggregateId", parcelId.toString());
    envelope.putObject("payload")
        .put("trackingRef", trackingRef)
        .put("status", status)
        .put("previousStatus", prevStatus)
        .put("actorRole", "DISPATCHER")
        .put("occurredAt", "2026-11-05T10:15:30.123456Z");
    return envelope.toString();
  }

  private String createdEventJson(UUID eventId, UUID parcelId, String trackingRef) {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", eventId.toString())
        .put("eventType", CREATED_TOPIC)
        .put("occurredAt", "2026-11-05T08:00:00Z")
        .put("aggregateId", parcelId.toString());
    envelope.putObject("payload")
        .put("trackingRef", trackingRef)
        .put("status", "CREATED")
        .put("source", "CUSTOMER_BOOKING")
        .put("occurredAt", "2026-11-05T08:00:00Z");
    return envelope.toString();
  }

  @Test
  @DisplayName("Consumes valid parcel event, creates tracking_event, and records processed_event")
  void validEventCreatesTrackingEventAndMarksProcessed() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String ref = newTrackingRef();
    String message = statusEventJson(eventId, parcelId, ref, "IN_TRANSIT", "PICKED_UP");

    kafka.send(STATUS_TOPIC, parcelId.toString(), message).get();

    await().atMost(WAIT).until(() ->
        !trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(ref).isEmpty());

    List<TrackingEvent> events = trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(ref);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getStatus()).isEqualTo(TrackingStatus.IN_TRANSIT);
    assertThat(events.get(0).getEventId()).isEqualTo(eventId);

    boolean processed = processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId);
    assertThat(processed).isTrue();
  }

  @Test
  @DisplayName("Duplicate event replay is idempotent and does not create duplicate tracking milestones")
  void duplicateEventIsIdempotentAndDoesNotDuplicateMilestone() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String ref = newTrackingRef();
    String message = statusEventJson(eventId, parcelId, ref, "PICKED_UP", "CREATED");

    // 1. Initial send
    kafka.send(STATUS_TOPIC, parcelId.toString(), message).get();
    await().atMost(WAIT).until(() ->
        !trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(ref).isEmpty());

    // 2. Replay duplicate event
    kafka.send(STATUS_TOPIC, parcelId.toString(), message).get();

    // 3. Send distinct next event on same partition to ensure the duplicate was fully processed
    UUID markerEventId = UUID.randomUUID();
    String markerMessage = statusEventJson(markerEventId, parcelId, ref, "DELIVERED", "PICKED_UP");
    kafka.send(STATUS_TOPIC, parcelId.toString(), markerMessage).get();

    await().atMost(WAIT).until(() ->
        trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(ref).size() >= 2);

    List<TrackingEvent> events = trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(ref);
    assertThat(events).hasSize(2);
    assertThat(events.get(0).getStatus()).isEqualTo(TrackingStatus.PICKED_UP);
    assertThat(events.get(1).getStatus()).isEqualTo(TrackingStatus.DELIVERED);

    Integer processedCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM tracking.processed_event WHERE event_id = ?", Integer.class, eventId);
    assertThat(processedCount).isEqualTo(1);
  }

  @Test
  @DisplayName("Malformed poison event is skipped and does not block subsequent valid events")
  void malformedJsonDoesNotBlockSubsequentEvents() throws Exception {
    UUID parcelId = UUID.randomUUID();
    String validRef = newTrackingRef();
    UUID validEventId = UUID.randomUUID();

    // 1. Send poison pill
    kafka.send(STATUS_TOPIC, parcelId.toString(), "{\"malformed\":json-not-valid}").get();

    // 2. Send healthy event
    String validMessage = createdEventJson(validEventId, parcelId, validRef);
    kafka.send(CREATED_TOPIC, parcelId.toString(), validMessage).get();

    await().atMost(WAIT).until(() ->
        !trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(validRef).isEmpty());

    List<TrackingEvent> events = trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(validRef);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventId()).isEqualTo(validEventId);
  }

  @Test
  @DisplayName("Database failure rolls back transaction so event is not marked processed, allowing replay")
  void databaseFailureRollsBackAndAllowsReplay() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String ref = newTrackingRef();
    String message = statusEventJson(eventId, parcelId, ref, "DELIVERED", "OUT_FOR_DELIVERY");

    // Install temporary trigger that causes database failure during event recording
    jdbc.execute("""
        CREATE FUNCTION tracking.test_reject_event() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'simulated db failure'; END; $$
        """);
    jdbc.execute("""
        CREATE TRIGGER test_reject_event BEFORE INSERT ON tracking.tracking_event
        FOR EACH ROW EXECUTE FUNCTION tracking.test_reject_event()
        """);

    try {
      kafka.send(STATUS_TOPIC, parcelId.toString(), message).get();
      // Allow consumer retries to execute and fail
      Thread.sleep(1500);

      // Verify event was NOT marked processed due to transactional rollback
      boolean processed = processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId);
      assertThat(processed).isFalse();
    } finally {
      // Remove temporary trigger
      jdbc.execute("DROP TRIGGER test_reject_event ON tracking.tracking_event");
      jdbc.execute("DROP FUNCTION tracking.test_reject_event()");
    }

    // Now replay / re-send the event
    kafka.send(STATUS_TOPIC, parcelId.toString(), message).get();

    await().atMost(WAIT).until(() ->
        !trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(ref).isEmpty());

    List<TrackingEvent> events = trackingEventRepository.findByTrackingRefOrderByOccurredAtAsc(ref);
    assertThat(events).hasSize(1);
    assertThat(events.get(0).getEventId()).isEqualTo(eventId);
    assertThat(processedEventRepository.existsByConsumerNameAndEventId("tracking-reporting-service", eventId)).isTrue();
  }
}

