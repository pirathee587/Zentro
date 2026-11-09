package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.zentro.parcelpricing.config.OutboxHealthIndicator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end event publishing with a throwaway PostgreSQL and a real Kafka broker (never the shared database).
 * Covers delivery, key, headers, ordering, a broker outage with retry, and duplicate handling by an idempotent
 * consumer, which is the consumer side of the contract. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext // stop the publisher's scheduler with this class instead of keeping it in the context cache
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
class OutboxKafkaIntegrationTest {
  private static final String STATUS_TOPIC = "parcel.status.updated";
  private static final String CREATED_TOPIC = "parcel.created";

  @Test
  void requestContextSurvivesStoredOutboxAndKafkaPublication() throws Exception {
    String trace = "0123456789abcdef0123456789abcdef";
    String body = mvc.perform(post("/api/v1/parcels").with(as("trace-customer", "CUSTOMER"))
        .header("X-Correlation-Id", "outbox-request-143")
        .header("traceparent", "00-" + trace + "-0123456789abcdef-01")
        .contentType(MediaType.APPLICATION_JSON).content(BOOKING))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    UUID parcelId = UUID.fromString(objectMapper.readTree(body).get("id").asText());
    var record = awaitRecords(parcelId, 1).getFirst();
    var stored = jdbc.queryForMap("SELECT correlation_id, traceparent FROM parcel.outbox_event WHERE aggregate_id = ?", parcelId);
    assertThat(stored.get("correlation_id")).isEqualTo("outbox-request-143");
    assertThat((String) stored.get("traceparent")).startsWith("00-" + trace + "-");
    assertThat(header(record, "X-Correlation-Id")).isEqualTo("outbox-request-143");
    assertThat(header(record, "traceparent")).startsWith("00-" + trace + "-").isNotEqualTo(stored.get("traceparent"));
    assertThat(record.value()).doesNotContain("correlation_id", "traceparent", "tracestate");
  }
  private static final String BOOKING = """
      {
        "sender": {"name": "Kafka Sender", "phone": "0771234567", "address": "12 Kafka Road, Colombo", "city": "Colombo"},
        "recipient": {"name": "Kafka Recipient", "phone": "+94712345678", "address": "45 Kafka Street, Kandy", "city": "Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Small Box", "size": "SMALL"},
        "destinationType": "OTHER_DISTRICT"
      }
      """;

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
    registry.add("spring.kafka.producer.properties[max.block.ms]", () -> "2000");
    registry.add("spring.kafka.producer.properties[request.timeout.ms]", () -> "2000");
    registry.add("spring.kafka.producer.properties[delivery.timeout.ms]", () -> "4000");
    registry.add("zentro.outbox.enabled", () -> "true");
    registry.add("zentro.outbox.poll-interval-ms", () -> "200");
    registry.add("zentro.outbox.send-timeout-ms", () -> "2000");
    registry.add("zentro.outbox.retry-base-delay-ms", () -> "200");
    registry.add("zentro.outbox.retry-max-delay-ms", () -> "1000");
    registry.add("zentro.outbox.max-attempts", () -> "100");
    registry.add("zentro.outbox.topic-partitions", () -> "3");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;
  @Autowired KafkaTemplate<String, String> kafkaTemplate;
  @Autowired OutboxHealthIndicator outboxHealth;
  @MockBean JwtDecoder jwtDecoder;

  KafkaConsumer<String, String> consumer;
  final List<ConsumerRecord<String, String>> received = new ArrayList<>();

  @BeforeEach
  void openConsumer() {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(List.of(STATUS_TOPIC, CREATED_TOPIC));
  }

  @AfterEach
  void closeConsumer() {
    consumer.close();
  }

  private static RequestPostProcessor as(String subject, String role) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
  }

  private UUID book() throws Exception {
    String body = mvc.perform(post("/api/v1/parcels").with(as("kafka-customer", "CUSTOMER"))
            .contentType(MediaType.APPLICATION_JSON).content(BOOKING))
        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    return UUID.fromString(objectMapper.readTree(body).get("id").asText());
  }

  private void move(UUID parcelId, String newStatus) throws Exception {
    mvc.perform(patch("/api/v1/parcels/" + parcelId + "/status").with(as("kafka-dispatcher", "DISPATCHER"))
            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + newStatus + "\"}"))
        .andExpect(status().isOk());
  }

  /** Polls Kafka until {@code count} records with this key arrived; returns them in arrival order. */
  private List<ConsumerRecord<String, String>> awaitRecords(UUID parcelId, int count) {
    await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ZERO).until(() -> {
      consumer.poll(Duration.ofMillis(250)).forEach(received::add);
      return recordsFor(parcelId).size() >= count;
    });
    return recordsFor(parcelId);
  }

  private List<ConsumerRecord<String, String>> recordsFor(UUID parcelId) {
    return received.stream().filter(r -> parcelId.toString().equals(r.key())).toList();
  }

  private static String header(ConsumerRecord<String, String> record, String name) {
    return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
  }

  private String storedPayload(UUID eventId) {
    return jdbc.queryForObject("SELECT payload::text FROM parcel.outbox_event WHERE event_id = ?", String.class,
        eventId);
  }

  @Test
  @DisplayName("Booking and status changes arrive once each, in order, keyed by parcel id, with headers")
  void eventsArriveInOrderWithKeyAndHeaders() throws Exception {
    UUID parcelId = book();
    move(parcelId, "PICKED_UP");
    move(parcelId, "IN_TRANSIT");
    move(parcelId, "IN_TRANSIT"); // repeat: no new event

    List<ConsumerRecord<String, String>> records = awaitRecords(parcelId, 3);

    assertThat(records).extracting(ConsumerRecord::topic).containsExactly(CREATED_TOPIC, STATUS_TOPIC, STATUS_TOPIC);
    assertThat(records).extracting(r -> objectMapper.readTree(r.value()).get("payload").get("status").asText())
        .containsExactly("CREATED", "PICKED_UP", "IN_TRANSIT");
    assertThat(records.stream().map(ConsumerRecord::partition).distinct()).hasSize(1);
    for (ConsumerRecord<String, String> record : records) {
      JsonNode envelope = objectMapper.readTree(record.value());
      UUID eventId = UUID.fromString(header(record, "eventId"));
      assertThat(envelope.get("eventId").asText()).isEqualTo(eventId.toString());
      assertThat(header(record, "eventType")).isEqualTo(envelope.get("eventType").asText());
      assertThat(header(record, "schemaVersion")).isEqualTo("1");
      assertThat(envelope.get("aggregateId").asText()).isEqualTo(parcelId.toString());
      assertThat(objectMapper.readTree(record.value())).isEqualTo(objectMapper.readTree(storedPayload(eventId)));
      assertThat(record.value()).doesNotContain("Kafka Sender", "Kafka Recipient", "0771234567", "712345678",
          "Kafka Road", "Kafka Street", "kafka-customer", "kafka-dispatcher");
    }

    // No extra message for the repeated status, and every event is marked published.
    consumer.poll(Duration.ofSeconds(1)).forEach(received::add);
    assertThat(recordsFor(parcelId)).hasSize(3);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM parcel.outbox_event WHERE aggregate_id = ? AND published_at IS NULL",
        Integer.class, parcelId)).isZero();
  }

  @Test
  @DisplayName("Broker outage: the API still succeeds, attempts grow, nothing is lost, events arrive after recovery")
  void brokerOutageIsRetried() throws Exception {
    UUID parcelId = book();
    awaitRecords(parcelId, 1);

    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      move(parcelId, "PICKED_UP"); // the status change itself does not depend on Kafka
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT attempts, last_error, published_at FROM parcel.outbox_event
             WHERE aggregate_id = ? AND event_type = 'parcel.status.updated'
            """, parcelId);
        assertThat((Integer) row.get("attempts")).isPositive();
        assertThat((String) row.get("last_error")).isNotBlank();
        assertThat(row.get("published_at")).isNull();
      });
      assertThat(outboxHealth.health().getDetails().get("pending")).isInstanceOf(Long.class);
      assertThat((Long) outboxHealth.health().getDetails().get("pending")).isPositive();
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    List<ConsumerRecord<String, String>> records = awaitRecords(parcelId, 2);
    assertThat(records).extracting(r -> objectMapper.readTree(r.value()).get("payload").get("status").asText())
        .contains("PICKED_UP");
    await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.queryForObject(
        "SELECT COUNT(*) FROM parcel.outbox_event WHERE aggregate_id = ? AND published_at IS NULL",
        Integer.class, parcelId) == 0);
    assertThat(outboxHealth.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("Consumer contract: consumed events match the schema, and a duplicate delivery is processed once")
  void duplicateDeliveryIsProcessedOnce() throws Exception {
    UUID parcelId = book();
    move(parcelId, "PICKED_UP");
    List<ConsumerRecord<String, String>> original = awaitRecords(parcelId, 2);

    // Simulate an at-least-once redelivery of the status event (same eventId, same body).
    ConsumerRecord<String, String> statusEvent = original.get(1);
    kafkaTemplate.send(STATUS_TOPIC, statusEvent.key(), statusEvent.value()).get();
    List<ConsumerRecord<String, String>> withDuplicate = awaitRecords(parcelId, 3);

    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    Path contracts = Path.of("..", "..", "contracts", "events").toAbsolutePath().normalize();
    Map<String, JsonSchema> schemas = Map.of(
        STATUS_TOPIC, factory.getSchema(SchemaLocation.of(
            contracts.resolve("parcel-status-updated.schema.json").toUri().toString()), config),
        CREATED_TOPIC, factory.getSchema(SchemaLocation.of(
            contracts.resolve("parcel-created.schema.json").toUri().toString()), config));

    IdempotentConsumer sample = new IdempotentConsumer();
    for (ConsumerRecord<String, String> record : withDuplicate) {
      JsonNode envelope = objectMapper.readTree(record.value());
      assertThat(schemas.get(record.topic()).validate(envelope)).isEmpty();
      sample.handle(envelope);
    }
    assertThat(withDuplicate).hasSize(3);
    assertThat(sample.applied).containsExactly("CREATED", "PICKED_UP");
  }

  /** Minimal consumer showing the required pattern: remember processed eventIds and skip repeats. */
  static final class IdempotentConsumer {
    final Set<String> processedEventIds = new HashSet<>();
    final List<String> applied = new ArrayList<>();

    void handle(JsonNode envelope) {
      if (!processedEventIds.add(envelope.get("eventId").asText())) {
        return;
      }
      applied.add(envelope.get("payload").get("status").asText());
    }
  }
}
