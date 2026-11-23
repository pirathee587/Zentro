package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Gateway + real S1/S2/S3/S4/S5 + isolated PostgreSQL/Kafka/Keycloak, never shared resources. */
@Testcontainers
@EnabledIfSystemProperty(named = "zentro.e2e.kafkaStatus", matches = "true")
class KafkaParcelStatusWorkflowIT extends DisposableGatewayStack {
  static final String ROOT = "/api/v1/courier/deliveries/";
  static String courierToken, strangerToken, courierId;

  @BeforeAll static void addCourierAndTrackingServices() throws Exception {
    // The superclass owns S1, S2, S5, gateway and disposable infrastructure.
    // Refuse to replace any other application listening on the remaining ports.
    try (var p3 = new ServerSocket(fixturePort(18083)); var p4 = new ServerSocket(fixturePort(18084))) { }
    createDatabase("dispatch_user", "dispatch-only", "dispatch_courier_db");
    createDatabase("tracking_user", "tracking-only", "tracking_reporting_db");
    var parcel = new HashMap<>(parcelConfiguration);
    parcel.put("ZENTRO_ASSIGNMENT_ELIGIBLE_STATUSES", "CREATED");
    parcel.put("DISPATCH_SERVICE_BASE_URL", fixtureUrl(18083));
    parcel.put("OUTBOX_POLL_INTERVAL_MS", "200");
    parcel.put("OUTBOX_SEND_TIMEOUT_MS", "1000");
    parcel.put("OUTBOX_RETRY_BASE_DELAY_MS", "200");
    parcelConfiguration = parcel;
    restartParcel();
    var dispatch = serviceEnvironment("dispatch_courier_db", "dispatch_user", "dispatch-only");
    dispatch.put("GRPC_SERVER_PORT", Integer.toString(fixtureGrpcPort(9083)));
    dispatch.put("IDENTITY_GRPC_PORT", Integer.toString(fixtureGrpcPort(9081)));
    dispatch.put("PARCEL_GRPC_PORT", Integer.toString(fixtureGrpcPort(9082)));
    dispatch.putAll(Map.of("IDENTITY_GRPC_HOST", "127.0.0.1", "PARCEL_GRPC_HOST", "127.0.0.1",
        "PARCEL_BASE_URL", fixtureUrl(18082), "ZENTRO_IDENTITY_BASE_URL", fixtureUrl(18081),
        "POD_TOKEN_URI", issuer + "/protocol/openid-connect/token", "POD_CLIENT_ID", "zentro-dispatch-service",
        "POD_CLIENT_SECRET", DISPATCH_SERVICE_SECRET, "POD_WORKER_ENABLED", "false", "OUTBOX_ENABLED", "false"));
    s3 = launch("dispatch-courier-service", fixturePort(18083), dispatch);
    var tracking = serviceEnvironment("tracking_reporting_db", "tracking_user", "tracking-only");
    tracking.put("PARCEL_GRPC_PORT", Integer.toString(fixtureGrpcPort(9082)));
    tracking.put("DISPATCH_GRPC_PORT", Integer.toString(fixtureGrpcPort(9083)));
    tracking.putAll(Map.of("KAFKA_CONSUMER_ENABLED", "true", "PARCEL_GRPC_HOST", "127.0.0.1",
        "DISPATCH_GRPC_HOST", "127.0.0.1", "SERVICE_CLIENT_ID", "zentro-tracking-service",
        "SERVICE_CLIENT_SECRET", TRACKING_SERVICE_SECRET));
    s4 = launch("tracking-reporting-service", fixturePort(18084), tracking);
    stop(gateway);
    gateway = launch("api-gateway", fixturePort(18080), Map.of("GATEWAY_PORT", Integer.toString(fixturePort(18080)),
        "S1_URI", fixtureUrl(18081), "S2_URI", fixtureUrl(18082),
        "S3_URI", fixtureUrl(18083), "S4_URI", fixtureUrl(18084),
        "S5_URI", fixtureUrl(18085), "JWT_ISSUER_URI", issuer,
        "JWT_JWKS_URI", jwksUri(), "JWT_AUDIENCE", "zentro-api"));
    String secret = "fixture-courier-" + UUID.randomUUID();
    createServiceClient("status-courier", secret, "COURIER");
    courierToken = tokenForClient("status-courier", secret);
    courierId = request("POST", "/api/v1/courier/profile/provision", courierToken, "{}", 200).path("id").asText();
    createServiceClient("status-other-courier", secret, "COURIER");
    strangerToken = tokenForClient("status-other-courier", secret);
    request("POST", "/api/v1/courier/profile/provision", strangerToken, "{}", 200);
    // JVM/container startup can outlive the tokens minted by the superclass.
    customerToken = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);
    dispatcherToken = tokenForClient(DISPATCHER_CLIENT, DISPATCHER_SECRET);
  }

  static Map<String, String> serviceEnvironment(String db, String user, String password) {
    var env = new HashMap<String, String>();
    env.putAll(Map.of("DB_URL", jdbcUrl(db), "DB_USER", user, "DB_PASSWORD", password,
        "JWT_ISSUER_URI", issuer, "JWT_JWKS_URI", jwksUri(), "JWT_AUDIENCE", "zentro-api",
        "KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers(), "SPRING_CONFIG_IMPORT", "",
        "SERVICE_TOKEN_URI", issuer + "/protocol/openid-connect/token"));
    env.put("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "true");
    return env;
  }

  @Test void customerDeliveryStreamsAndDeduplicatesWithoutMerchant() throws Exception {
    var booking = book(false, customerToken);
    String parcel = booking.path("id").asText(), ref = booking.path("trackingRef").asText();
    assign(parcel);
    long before = count("parcel", "SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id=?", parcel);
    request("PATCH", ROOT + parcel + "/status", strangerToken, "{\"status\":\"PICKED_UP\"}", 403);
    assertThat(count("parcel", "SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id=?", parcel)).isEqualTo(before);
    assertThat(count("parcel", "SELECT count(*) FROM parcel.parcel_status_history WHERE parcel_id=?", parcel)).isZero();
    var delivered = new CompletableFuture<String>();
    var streamRequest = HttpRequest.newBuilder(URI.create(fixtureUrl(18080) + "/api/v1/tracking/" + ref + "/events"))
        .header("Accept", "text/event-stream").header("Authorization", "Bearer " + customerToken).GET().build();
    var stream = HTTP.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
    assertThat(stream.statusCode()).isEqualTo(200);
    try (var input = stream.body()) {
      Thread reader = Thread.ofVirtual().start(() -> {
        try (var lines = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
          for (String line; (line = lines.readLine()) != null;) {
            if (line.startsWith("data:") && line.contains("DELIVERED")) { delivered.complete(line); return; }
          }
        } catch (Exception error) { delivered.completeExceptionally(error); }
      });
      for (String state : List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED")) {
        update(parcel, state);
        assertThat(request("PATCH", ROOT + parcel + "/status", courierToken,
            JSON.writeValueAsString(Map.of("status", state)), 200).path("changed").asBoolean()).isFalse();
        awaitProcessed(parcel, state, !state.equals("IN_TRANSIT"));
      }
      assertThat(delivered.get(30, TimeUnit.SECONDS)).contains(ref).doesNotContain("phone", "address", "customerSubject");
      reader.join(1000);
    }
    assertThat(count("parcel", "SELECT count(*) FROM parcel.parcel_status_history WHERE parcel_id=?", parcel)).isEqualTo(4);
    assertThat(count("notification", "SELECT count(*) FROM notification.webhook_delivery WHERE parcel_id=?", parcel)).isZero();
    JsonNode event = lastEvent(parcel);
    publishDuplicate(parcel, event);
    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      assertThat(count("tracking", "SELECT count(*) FROM tracking.tracking_event WHERE event_id=?", event.path("eventId").asText())).isEqualTo(1);
      assertThat(count("notification", "SELECT count(*) FROM notification.notification_attempt WHERE event_id=?", event.path("eventId").asText())).isEqualTo(1);
      assertThat(count("notification", "SELECT count(*) FROM notification.delivery_attempt_history WHERE event_id=? AND delivery_kind='NOTIFICATION'", event.path("eventId").asText())).isEqualTo(1);
    });
    assertThat(request("GET", "/api/v1/tracking/" + ref, null, null, 200).path("rawStatus").asText()).isEqualTo("DELIVERED");
  }

  @Test void committedFailureSurvivesKafkaOutage() throws Exception {
    var booking = book(false, customerToken); String parcel = booking.path("id").asText();
    assign(parcel); update(parcel, "PICKED_UP"); awaitProcessed(parcel, "PICKED_UP", true);
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      update(parcel, "FAILED"); // HTTP succeeds while Kafka cannot acknowledge.
      assertThat(count("parcel", "SELECT count(*) FROM parcel.parcel WHERE id=? AND status='FAILED'", parcel)).isEqualTo(1);
      assertThat(count("parcel", "SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id=? AND event_type='parcel.status.updated' AND published_at IS NULL", parcel)).isGreaterThan(0);
      await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
          assertThat(count("parcel", "SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id=? AND payload->'payload'->>'status'='FAILED' AND published_at IS NULL AND attempts>0", parcel)).isEqualTo(1));
    } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
    awaitProcessed(parcel, "FAILED", true);
  }

  @Test void merchantWebhookFailureDoesNotRollBackDeliveryAndCanRecover() throws Exception {
    String client = "status-merchant-" + UUID.randomUUID(), secret = UUID.randomUUID().toString();
    String subject = createServiceClient(client, secret, "CUSTOMER");
    String token = tokenForClient(client, secret);
    var application = request("POST", "/api/v1/merchants", token,
        JSON.writeValueAsString(Map.of("businessName", "Jaffna fixture", "businessRegNo", UUID.randomUUID().toString(), "contactPhone", "+94770000000")), 200);
    request("PATCH", "/api/v1/admin/merchants/" + application.path("id").asText(), dispatcherToken, "{\"decision\":\"APPROVED\"}", 200);
    simulateManualMerchantRoleAssignment(subject); token = tokenForClient(client, secret);
    var recovering = new AtomicBoolean(false);
    try (var receiver = new MockWebServer()) {
      receiver.setDispatcher(new Dispatcher() {
        @Override public MockResponse dispatch(RecordedRequest req) {
          try {
            boolean terminal = JSON.readTree(req.getBody().clone().readUtf8()).path("payload").path("status").asText().equals("DELIVERED");
            return new MockResponse().setResponseCode(terminal && !recovering.get() ? 503 : 200);
          } catch (Exception invalid) { return new MockResponse().setResponseCode(400); }
        }
      });
      receiver.start();
      var subscription = request("POST", "/api/v1/merchant/webhooks", token,
          JSON.writeValueAsString(Map.of("url", receiver.url("/status").toString())), 201);
      String signingSecret = subscription.path("secret").asText();
      var booking = book(true, token); String parcel = booking.path("id").asText(); assign(parcel);
      for (String state : List.of("PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED")) update(parcel, state);
      awaitProcessed(parcel, "DELIVERED", true);
      var event = lastEvent(parcel); String id = event.path("eventId").asText();
      await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
          assertThat(count("notification", "SELECT count(*) FROM notification.webhook_delivery WHERE event_id=? AND status='FAILED'", id)).isEqualTo(1));
      assertThat(count("parcel", "SELECT count(*) FROM parcel.parcel WHERE id=? AND status='DELIVERED'", parcel)).isEqualTo(1);
      assertThat(count("notification", "SELECT count(*) FROM notification.delivery_attempt_history WHERE event_id=? AND delivery_kind='WEBHOOK'", id)).isGreaterThan(1);
      RecordedRequest hook;
      do { hook = receiver.takeRequest(10, TimeUnit.SECONDS); assertThat(hook).isNotNull(); }
      while (!id.equals(hook.getHeader("X-SwiftDrop-Event-Id")));
      assertThat(hook.getHeader("X-SwiftDrop-Signature")).isEqualTo(hmac(signingSecret,
          hook.getHeader("X-SwiftDrop-Timestamp"), hook.getBody().readUtf8()));
      recovering.set(true);
      String deliveryId = scalar("notification", "SELECT id::text FROM notification.webhook_delivery WHERE event_id=?", id);
      request("POST", "/api/v1/admin/webhook-deliveries/" + deliveryId + "/replay", dispatcherToken, null, 204);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
          assertThat(count("notification", "SELECT count(*) FROM notification.webhook_delivery WHERE event_id=? AND status='SUCCEEDED'", id)).isEqualTo(1));
      long attempts = count("notification", "SELECT count(*) FROM notification.delivery_attempt_history WHERE event_id=? AND delivery_kind='WEBHOOK'", id);
      publishDuplicate(parcel, event);
      await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
        assertThat(count("notification", "SELECT count(*) FROM notification.webhook_delivery WHERE event_id=?", id)).isEqualTo(1);
        assertThat(count("notification", "SELECT count(*) FROM notification.delivery_attempt_history WHERE event_id=? AND delivery_kind='WEBHOOK'", id)).isEqualTo(attempts);
      });
    }
  }

  static JsonNode request(String method, String path, String token, String body, int expected) throws Exception {
    var response = call(method, path, token, body);
    assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expected);
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }
  static JsonNode book(boolean merchant, String token) throws Exception {
    var body = (ObjectNode) JSON.readTree(orderBody());
    ((ObjectNode) body.path("sender")).put("city", "Jaffna");
    ((ObjectNode) body.path("recipient")).put("city", "Kandy");
    return request("POST", merchant ? "/api/v1/merchant/orders" : "/api/v1/parcels", token, body.toString(), 201);
  }
  static void assign(String parcel) throws Exception {
    request("POST", "/api/v1/dispatch/assignments", dispatcherToken,
        JSON.writeValueAsString(Map.of("parcelId", parcel, "courierId", courierId)), 201);
    request("POST", ROOT + parcel + "/accept", courierToken, "{}", 200);
  }
  static void update(String parcel, String status) throws Exception {
    request("PATCH", ROOT + parcel + "/status", courierToken,
        JSON.writeValueAsString(Map.of("status", status, "reason", status.equals("FAILED") ? "Recipient unavailable" : "")), 200);
  }
  static JsonNode lastEvent(String parcel) throws Exception {
    return JSON.readTree(scalar("parcel", "SELECT payload::text FROM parcel.outbox_event WHERE aggregate_id=? AND event_type='parcel.status.updated' ORDER BY occurred_at DESC LIMIT 1", parcel));
  }
  static void awaitProcessed(String parcel, String state, boolean sent) {
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      String event = scalar("parcel", "SELECT event_id::text FROM parcel.outbox_event WHERE aggregate_id=? AND payload->'payload'->>'status'='" + state + "' AND published_at IS NOT NULL", parcel);
      assertThat(count("tracking", "SELECT count(*) FROM tracking.tracking_event WHERE event_id=?", event)).isEqualTo(1);
      assertThat(count("notification", "SELECT count(*) FROM notification.notification_attempt WHERE event_id=? AND status='" + (sent ? "SENT" : "SKIPPED") + "'", event)).isEqualTo(1);
    });
  }
  static void publishDuplicate(String parcel, JsonNode event) throws Exception {
    try (var producer = new KafkaProducer<String, String>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()), new StringSerializer(), new StringSerializer())) {
      var record = new ProducerRecord<String, String>("parcel.status.updated", parcel, event.toString());
      record.headers().add("schemaVersion", new byte[] {'1'});
      record.headers().add("eventId", event.path("eventId").asText().getBytes(StandardCharsets.UTF_8));
      producer.send(record).get(10, TimeUnit.SECONDS);
    }
  }
  static long count(String service, String sql, String id) throws Exception { return Long.parseLong(scalar(service, sql, id)); }
  static String scalar(String service, String sql, String id) throws Exception {
    String db = switch (service) { case "parcel" -> "parcel_pricing_db"; case "tracking" -> "tracking_reporting_db"; default -> "notification_integration_db"; };
    String user = switch (service) { case "parcel" -> "parcel_pricing_user"; case "tracking" -> "tracking_user"; default -> "notification_integration_user"; };
    String password = switch (service) { case "parcel" -> "parcel-pricing-only"; case "tracking" -> "tracking-only"; default -> "notification-integration-only"; };
    try (var connection = DriverManager.getConnection(jdbcUrl(db), user, password); var statement = connection.prepareStatement(sql)) {
      statement.setObject(1, UUID.fromString(id));
      try (var rows = statement.executeQuery()) { assertThat(rows.next()).as("Fixture fact becomes available").isTrue(); return rows.getString(1); }
    }
  }
}
