package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import com.zentro.notificationintegration.webhook.client.IdentityMerchantClient;
import com.zentro.notificationintegration.webhook.service.WebhookSubscriptionService;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * End to end: parcel events on Kafka -> webhook consumer -> PostgreSQL -> signed POST to a local receiver
 * (JDK HttpServer on localhost, allowed only through the development setting). Covers success with a valid
 * signature, duplicates, retries after 500 and timeouts, permanent 400, skipped events, per-parcel order and
 * malformed messages. Never touches the shared database. Skipped when Docker is not available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext // stop the listener and retry scheduler with the class, before its containers go away
class WebhookDeliveryIntegrationTest {
  private static final String CREATED_TOPIC = "parcel.created";
  private static final String STATUS_TOPIC = "parcel.status.updated";
  private static final Duration WAIT = Duration.ofSeconds(60);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  /** Scripted receiver: per path, a queue of responses (status code, or SLOW = answer after the read timeout). */
  static final int SLOW = -1;
  static HttpServer receiver;
  static final Map<String, Deque<Integer>> SCRIPT = new ConcurrentHashMap<>();
  static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();

  record Received(String path, Map<String, String> headers, String body) {}

  @BeforeAll
  static void startReceiver() throws IOException {
    receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    receiver.setExecutor(Executors.newCachedThreadPool());
    receiver.createContext("/", exchange -> {
      String body;
      try (InputStream in = exchange.getRequestBody()) {
        body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      Map<String, String> headers = new ConcurrentHashMap<>();
      exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values.get(0)));
      String path = exchange.getRequestURI().getPath();
      RECEIVED.add(new Received(path, headers, body));
      Deque<Integer> script = SCRIPT.get(path);
      Integer status = script == null ? null : script.poll();
      int code = status == null ? 200 : status;
      if (code == SLOW) {
        try {
          Thread.sleep(1500);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
        code = 200;
      }
      exchange.sendResponseHeaders(code, -1);
      exchange.close();
    });
    receiver.start();
  }

  @AfterAll
  static void stopReceiver() {
    receiver.stop(0);
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("spring.kafka.consumer.properties[metadata.max.age.ms]", () -> "1000");
    registry.add("zentro.webhooks.enabled", () -> "true");
    registry.add("zentro.webhooks.allow-insecure-local", () -> "true");
    registry.add("zentro.webhooks.secret-key", () -> "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
    registry.add("zentro.webhooks.retry.poll-interval-ms", () -> "200");
    registry.add("zentro.webhooks.retry.base-delay-ms", () -> "1000");
    registry.add("zentro.webhooks.retry.max-delay-ms", () -> "2000");
    registry.add("zentro.webhooks.http.read-timeout-ms", () -> "500");
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
  @Autowired WebhookSubscriptionService subscriptions;
  @MockBean IdentityMerchantClient identity; // approval is covered by the API tests
  @MockBean JwtDecoder jwtDecoder;

  /** Registers a local endpoint for a new merchant and returns {merchantId, path, secret}. */
  private String[] newMerchant(Integer... script) {
    String merchantId = "merchant-" + UUID.randomUUID();
    String path = "/hooks/" + merchantId;
    SCRIPT.put(path, new ArrayDeque<>(List.of(script)));
    String url = "http://localhost:" + receiver.getAddress().getPort() + path;
    String secret = subscriptions.register(merchantId, "token", url).secret();
    return new String[] {merchantId, path, secret};
  }

  private String statusEvent(UUID eventId, UUID parcelId, String status, String merchantId) {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", eventId.toString())
        .put("eventType", STATUS_TOPIC)
        .put("occurredAt", "2026-11-06T10:15:30.123456Z")
        .put("aggregateId", parcelId.toString());
    ObjectNode payload = envelope.putObject("payload")
        .put("trackingRef", "ZT-ABCDEFGH12")
        .put("previousStatus", "CREATED")
        .put("status", status)
        .put("actorRole", "DISPATCHER")
        .put("occurredAt", "2026-11-06T10:15:30.123456Z");
    if (merchantId != null) {
      payload.put("merchantId", merchantId);
    }
    return envelope.toString();
  }

  private String createdEvent(UUID eventId, UUID parcelId, String merchantId) {
    ObjectNode envelope = objectMapper.createObjectNode()
        .put("eventId", eventId.toString())
        .put("eventType", CREATED_TOPIC)
        .put("occurredAt", "2026-11-06T10:15:00Z")
        .put("aggregateId", parcelId.toString());
    envelope.putObject("payload")
        .put("trackingRef", "ZT-ABCDEFGH12")
        .put("status", "CREATED")
        .put("source", "MERCHANT_ORDER")
        .put("occurredAt", "2026-11-06T10:15:00Z")
        .put("merchantId", merchantId);
    return envelope.toString();
  }

  private void send(String topic, UUID parcelId, String value) throws Exception {
    kafka.send(topic, parcelId.toString(), value).get();
  }

  private List<Received> receivedAt(String path) {
    return RECEIVED.stream().filter(r -> r.path().equals(path)).toList();
  }

  private Map<String, Object> delivery(UUID eventId) {
    List<Map<String, Object>> rows = jdbc.queryForList("""
        SELECT status, attempts, response_status, last_error FROM notification.webhook_delivery WHERE event_id = ?
        """, eventId);
    return rows.isEmpty() ? Map.of() : rows.get(0);
  }

  private Map<String, Object> awaitDelivery(UUID eventId, String status) {
    await().atMost(WAIT).until(() -> status.equals(delivery(eventId).get("status")));
    return delivery(eventId);
  }

  private static String hmac(String secret, String data) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("Success: the exact envelope arrives with event headers and a valid signature; SUCCEEDED is recorded")
  void signedDelivery() throws Exception {
    String[] merchant = newMerchant();
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String message = createdEvent(eventId, parcelId, merchant[0]);

    send(CREATED_TOPIC, parcelId, message);

    Map<String, Object> row = awaitDelivery(eventId, "SUCCEEDED");
    assertThat(row).containsEntry("attempts", 1).containsEntry("response_status", 200);
    Received received = receivedAt(merchant[1]).get(0);
    assertThat(received.body()).isEqualTo(message);
    assertThat(received.headers()).containsEntry("content-type", "application/json")
        .containsEntry("x-swiftdrop-event-id", eventId.toString())
        .containsEntry("x-swiftdrop-event-type", "parcel.created");
    String timestamp = received.headers().get("x-swiftdrop-timestamp");
    assertThat(Long.parseLong(timestamp)).isCloseTo(System.currentTimeMillis() / 1000, org.assertj.core.data.Offset.offset(120L));
    assertThat(received.headers().get("x-swiftdrop-signature")).isEqualTo(hmac(merchant[2], timestamp + "." + message));
    assertThat(received.headers().get("x-swiftdrop-signature")).isNotEqualTo(hmac("whsec_wrong", timestamp + "." + message));
  }

  @Test
  @DisplayName("A duplicate event (same eventId) is delivered once")
  void duplicateDeliveredOnce() throws Exception {
    String[] merchant = newMerchant();
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String message = statusEvent(eventId, parcelId, "PICKED_UP", merchant[0]);

    send(STATUS_TOPIC, parcelId, message);
    awaitDelivery(eventId, "SUCCEEDED");
    send(STATUS_TOPIC, parcelId, message);
    UUID marker = UUID.randomUUID();
    send(STATUS_TOPIC, parcelId, statusEvent(marker, parcelId, "IN_TRANSIT", merchant[0]));
    awaitDelivery(marker, "SUCCEEDED"); // processed after the duplicate on the same partition

    assertThat(receivedAt(merchant[1])).extracting(r -> r.headers().get("x-swiftdrop-event-id"))
        .containsExactly(eventId.toString(), marker.toString());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification.webhook_delivery WHERE event_id = ?",
        Integer.class, eventId)).isEqualTo(1);
  }

  @Test
  @DisplayName("500 then 200: RETRY with the response code recorded, then SUCCEEDED on the second attempt")
  void retryThenSuccess() throws Exception {
    String[] merchant = newMerchant(500);
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(eventId, parcelId, "PICKED_UP", merchant[0]));

    Map<String, Object> retry = awaitDelivery(eventId, "RETRY");
    assertThat(retry).containsEntry("attempts", 1).containsEntry("response_status", 500)
        .containsEntry("last_error", "HTTP 500");
    Map<String, Object> done = awaitDelivery(eventId, "SUCCEEDED");
    assertThat(done).containsEntry("attempts", 2).containsEntry("response_status", 200);
    assertThat(done.get("last_error")).isNull();
    assertThat(receivedAt(merchant[1])).hasSize(2);
  }

  @Test
  @DisplayName("400: FAILED at once with the response code; no further attempts")
  void clientErrorFailsPermanently() throws Exception {
    String[] merchant = newMerchant(400, 400, 400);
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(eventId, parcelId, "PICKED_UP", merchant[0]));

    Map<String, Object> row = awaitDelivery(eventId, "FAILED");
    assertThat(row).containsEntry("attempts", 1).containsEntry("response_status", 400)
        .containsEntry("last_error", "HTTP 400");
    Thread.sleep(2500); // longer than the back-off: a retry would have happened by now
    assertThat(receivedAt(merchant[1])).hasSize(1);
  }

  @Test
  @DisplayName("A response slower than the read timeout is recorded as a timeout and retried")
  void timeoutIsRetried() throws Exception {
    String[] merchant = newMerchant(SLOW);
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(eventId, parcelId, "PICKED_UP", merchant[0]));

    Map<String, Object> retry = awaitDelivery(eventId, "RETRY");
    assertThat(retry).containsEntry("attempts", 1).containsEntry("last_error", "timeout");
    assertThat(retry.get("response_status")).isNull();
    assertThat(awaitDelivery(eventId, "SUCCEEDED")).containsEntry("attempts", 2);
  }

  @Test
  @DisplayName("Customer parcels (no merchantId) and disabled endpoints are skipped: nothing is sent")
  void skippedEvents() throws Exception {
    String[] disabled = newMerchant();
    subscriptions.disable(disabled[0]);
    UUID customerEvent = UUID.randomUUID();
    UUID disabledEvent = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String[] marker = newMerchant();
    UUID markerEvent = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(customerEvent, parcelId, "PICKED_UP", null));
    send(STATUS_TOPIC, parcelId, statusEvent(disabledEvent, parcelId, "IN_TRANSIT", disabled[0]));
    send(STATUS_TOPIC, parcelId, statusEvent(markerEvent, parcelId, "OUT_FOR_DELIVERY", marker[0]));
    awaitDelivery(markerEvent, "SUCCEEDED"); // same partition: the two events before it were handled

    assertThat(delivery(customerEvent)).isEmpty();
    assertThat(delivery(disabledEvent)).isEmpty();
    assertThat(receivedAt(disabled[1])).isEmpty();
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM notification.processed_event
         WHERE consumer = 'merchant-webhooks' AND event_id IN (?, ?)
        """, Integer.class, customerEvent, disabledEvent)).isEqualTo(2);
  }

  @Test
  @DisplayName("Per-parcel order: a later event waits until the earlier one for the same parcel succeeded")
  void parcelOrder() throws Exception {
    String[] merchant = newMerchant(503);
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();

    send(STATUS_TOPIC, parcelId, statusEvent(first, parcelId, "PICKED_UP", merchant[0]));
    awaitDelivery(first, "RETRY");
    send(STATUS_TOPIC, parcelId, statusEvent(second, parcelId, "IN_TRANSIT", merchant[0]));
    awaitDelivery(first, "SUCCEEDED");
    awaitDelivery(second, "SUCCEEDED");

    assertThat(receivedAt(merchant[1])).extracting(r -> r.headers().get("x-swiftdrop-event-id"))
        .containsExactly(first.toString(), first.toString(), second.toString());
  }

  @Test
  @DisplayName("A malformed message is recorded as rejected for the webhook consumer and does not block others")
  void malformedIsRejected() throws Exception {
    String[] merchant = newMerchant();
    UUID parcelId = UUID.randomUUID();
    UUID after = UUID.randomUUID();
    ObjectNode bad = (ObjectNode) objectMapper.readTree(statusEvent(UUID.randomUUID(), parcelId, "PICKED_UP", null));
    ((ObjectNode) bad.get("payload")).put("merchantId", 42);

    send(STATUS_TOPIC, parcelId, bad.toString());
    send(STATUS_TOPIC, parcelId, statusEvent(after, parcelId, "PICKED_UP", merchant[0]));

    awaitDelivery(after, "SUCCEEDED");
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM notification.rejected_event WHERE reason = 'merchant-webhooks: invalid merchantId'",
        Integer.class)).isEqualTo(1);
  }
}
