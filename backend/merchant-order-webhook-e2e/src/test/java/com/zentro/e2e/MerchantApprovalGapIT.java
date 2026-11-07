package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;

/**
 * Cross-service known-gap coverage. All business HTTP calls go through the real API gateway.
 * Keycloak's admin API is used only to seed fixture users and simulate the documented manual role grant.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class MerchantApprovalGapIT {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private static final String CLIENT_ID = "merchant-order-webhook-e2e";
  private static final String CUSTOMER_CLIENT = CLIENT_ID + "-customer";
  private static final String DISPATCHER_CLIENT = CLIENT_ID + "-dispatcher";
  private static final String CUSTOMER_SECRET = "customer-" + UUID.randomUUID();
  private static final String DISPATCHER_SECRET = "dispatcher-" + UUID.randomUUID();
  private static final String ADMIN_USER = "e2e-admin";
  private static final String ADMIN_PASSWORD = "e2e-admin-" + UUID.randomUUID();
  private static final String REALM = "zentro";
  private static final Path MODULE = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
  private static final Path REPO = MODULE.getParent().getParent();

  // One physical PostgreSQL container is a test-environment optimization only. Each service gets
  // a separate database and login, preserving the production database-per-service boundary.
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
      .withDatabaseName("e2e_admin").withUsername("e2e_admin").withPassword("e2e-admin-only");
  private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.8.0"))
      .withEnv("KAFKA_HEAP_OPTS", "-Xms128m -Xmx256m");
  private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.3.3"))
      .withExposedPorts(8080)
      .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USER)
      .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
      .withEnv("JAVA_OPTS_APPEND", "-Xms192m -Xmx384m")
      .withCopyFileToContainer(MountableFile.forHostPath(REPO.resolve("infra/keycloak/realm-export.json")),
          "/opt/keycloak/data/import/zentro-realm.json")
      .withCommand("start-dev", "--import-realm", "--http-enabled=true", "--hostname-strict=false")
      .waitingFor(Wait.forHttp("/realms/zentro/.well-known/openid-configuration").forStatusCode(200)
          .withStartupTimeout(Duration.ofMinutes(2)));
  private static Process s1;
  private static Process s2;
  private static Process s5;
  private static Process gateway;
  private static String issuer;
  private static String dispatcherToken;
  private static String customerToken;
  private static String customerId;
  private static String adminAccessToken;
  private static Instant adminAccessTokenExpiresAt = Instant.EPOCH;

  @BeforeAll
  static void startStack() throws Exception {
    Startables.deepStart(Stream.of(POSTGRES, KAFKA)).join();
    initializeDatabases();
    KEYCLOAK.start();
    issuer = keycloak() + "/realms/" + REALM;
    createTestClient();
    dispatcherToken = tokenForClient(DISPATCHER_CLIENT, DISPATCHER_SECRET);
    customerToken = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);
    startServices();
  }

  @AfterAll
  static void stopStack() {
    stop(gateway); stop(s5); stop(s2); stop(s1);
    KEYCLOAK.stop(); KAFKA.stop(); POSTGRES.stop();
  }

  @Test
  void findingK4_approvalDoesNotGrantMerchantRoleUntilManualKeycloakAssignment() throws Exception {
    String applyBody = "{\"businessName\":\"E2E " + UUID.randomUUID()
        + "\",\"businessRegNo\":\"E2E-" + UUID.randomUUID() + "\",\"contactPhone\":\"+94771234567\"}";
    HttpResponse<String> applied = call("POST", "/api/v1/merchants", customerToken, applyBody);
    assertThat(applied.statusCode()).isIn(200, 201);
    JsonNode application = JSON.readTree(applied.body());
    String applicationId = application.path("id").asText();
    assertThat(application.path("status").asText()).isEqualTo("PENDING");

    HttpResponse<String> approval = call("PATCH", "/api/v1/admin/merchants/" + applicationId,
        dispatcherToken, "{\"decision\":\"APPROVED\"}");
    assertThat(approval.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(approval.body()).path("status").asText()).isEqualTo("APPROVED");

    String freshBeforeAssignment = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);
    assertThat(realmRoles(freshBeforeAssignment)).doesNotContain("MERCHANT");
    assertThat(call("POST", "/api/v1/merchant/orders", freshBeforeAssignment, orderBody()).statusCode()).isEqualTo(403);
    assertThat(call("POST", "/api/v1/merchant/webhooks", freshBeforeAssignment,
        "{\"url\":\"https://example.org/hooks\"}").statusCode()).isEqualTo(403);

    simulateManualMerchantRoleAssignment(customerId);
    assertThat(realmRoles(freshBeforeAssignment)).doesNotContain("MERCHANT");
    String afterReLogin = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);
    assertThat(realmRoles(afterReLogin)).contains("MERCHANT");
    try (MockWebServer receiver = new MockWebServer()) {
      receiver.start();
      receiver.enqueue(new MockResponse().setResponseCode(503));
      receiver.enqueue(new MockResponse().setResponseCode(200));
      HttpResponse<String> registered = call("POST", "/api/v1/merchant/webhooks", afterReLogin,
          "{\"url\":\"http://127.0.0.1:" + receiver.getPort() + "/events\"}");
      assertThat(registered.statusCode()).isEqualTo(201);
      JsonNode secretResponse = JSON.readTree(registered.body());
      String secret = secretResponse.path("secret").asText();
      assertThat(secret).startsWith("whsec_");
      HttpResponse<String> subscription = call("GET", "/api/v1/merchant/webhooks", afterReLogin, null);
      assertThat(subscription.statusCode()).isEqualTo(200);
      assertThat(JSON.readTree(subscription.body()).has("secret")).isFalse();

      String submittedOrder = orderBody();
      HttpResponse<String> createdOrder = call("POST", "/api/v1/merchant/orders", afterReLogin, submittedOrder);
      assertThat(createdOrder.statusCode()).isEqualTo(201);
      JsonNode order = JSON.readTree(createdOrder.body());
      assertThat(order.path("trackingRef").asText()).startsWith("ZT-");

      RecordedRequest firstAttempt = receiver.takeRequest(30, TimeUnit.SECONDS);
      RecordedRequest secondAttempt = receiver.takeRequest(30, TimeUnit.SECONDS);
      assertThat(firstAttempt).isNotNull();
      assertThat(secondAttempt).isNotNull();
      assertThat(firstAttempt.getHeader("X-SwiftDrop-Event-Type")).isEqualTo("parcel.created");
      assertThat(firstAttempt.getHeader("X-SwiftDrop-Event-Id")).isEqualTo(secondAttempt.getHeader("X-SwiftDrop-Event-Id"));
      assertThat(firstAttempt.getHeader("X-SwiftDrop-Signature")).isEqualTo(
          hmac(secret, firstAttempt.getHeader("X-SwiftDrop-Timestamp"), firstAttempt.getBody().readUtf8()));
      assertThat(secondAttempt.getHeader("X-SwiftDrop-Signature")).isEqualTo(
          hmac(secret, secondAttempt.getHeader("X-SwiftDrop-Timestamp"), secondAttempt.getBody().readUtf8()));
      assertThat(firstAttempt.getHeader("X-Delivery-Id")).isNull(); // K5: current wire contract has no delivery-id header.
    }
  }

  @Test
  void rejectedMerchant_remainsBlockedAfterManualRoleAssignment_noParcelIsCreated() throws Exception {
    String client = CLIENT_ID + "-rejected-" + UUID.randomUUID();
    String secret = "rejected-" + UUID.randomUUID();
    String rejectedUserId = createServiceClient(client, secret, "CUSTOMER");
    String customer = tokenForClient(client, secret);
    HttpResponse<String> applied = call("POST", "/api/v1/merchants", customer,
        "{\"businessName\":\"Rejected E2E\",\"businessRegNo\":\"REJECTED-" + UUID.randomUUID()
            + "\",\"contactPhone\":\"+94771234567\"}");
    assertThat(applied.statusCode()).isIn(200, 201);
    String applicationId = JSON.readTree(applied.body()).path("id").asText();
    HttpResponse<String> rejected = call("PATCH", "/api/v1/admin/merchants/" + applicationId,
        dispatcherToken, "{\"decision\":\"REJECTED\",\"rejectionReason\":\"E2E rejection\"}");
    assertThat(rejected.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(rejected.body()).path("status").asText()).isEqualTo("REJECTED");

    // Even a manual Keycloak role mapping cannot bypass S1's approval status check in S2.
    simulateManualMerchantRoleAssignment(rejectedUserId);
    String roleBearingToken = tokenForClient(client, secret);
    assertThat(realmRoles(roleBearingToken)).contains("MERCHANT");
    assertThat(call("POST", "/api/v1/merchant/orders", roleBearingToken, orderBody()).statusCode()).isEqualTo(403);
  }

  /** Test-fixture equivalent of a manual Keycloak admin action; production approval does not do this today. */
  private static void simulateManualMerchantRoleAssignment(String userId) throws Exception {
    simulateRoleAssignment(adminToken(), userId, "MERCHANT");
  }

  private static void simulateRoleAssignment(String admin, String userId, String roleName) throws Exception {
    JsonNode role = send("GET", keycloak() + "/admin/realms/zentro/roles/" + roleName, admin, null, 200);
    send("POST", keycloak() + "/admin/realms/zentro/users/" + userId + "/role-mappings/realm", admin,
        JSON.writeValueAsString(List.of(role)), 204);
  }

  private static void startServices() throws Exception {
    String base = issuer;
    s1 = launch("identity-partner-service", 18081, Map.of(
        "DB_URL", jdbcUrl("identity_db"), "DB_USER", "identity_user", "DB_PASSWORD", "identity-only",
        "JWT_ISSUER_URI", base, "JWT_JWKS_URI", base + "/protocol/openid-connect/certs", "JWT_AUDIENCE", "zentro-api",
        "GRPC_SERVER_ENABLED", "false"));
    s2 = launch("parcel-pricing-service", 18082, Map.of(
        "DB_URL", jdbcUrl("parcel_pricing_db"), "DB_USER", "parcel_pricing_user", "DB_PASSWORD", "parcel-pricing-only",
        "JWT_ISSUER_URI", base, "JWT_JWKS_URI", base + "/protocol/openid-connect/certs", "JWT_AUDIENCE", "zentro-api",
        "ZENTRO_IDENTITY_BASE_URL", "http://127.0.0.1:18081", "KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers(),
        "OUTBOX_PUBLISHER_ENABLED", "true", "SERVER_PORT", "18082"));
    s5 = launch("notification-integration-service", 18085, Map.ofEntries(
        Map.entry("DB_URL", jdbcUrl("notification_integration_db")), Map.entry("DB_USER", "notification_integration_user"), Map.entry("DB_PASSWORD", "notification-integration-only"),
        Map.entry("JWT_ISSUER_URI", base), Map.entry("JWT_JWKS_URI", base + "/protocol/openid-connect/certs"), Map.entry("JWT_AUDIENCE", "zentro-api"),
        Map.entry("ZENTRO_IDENTITY_BASE_URL", "http://127.0.0.1:18081"), Map.entry("KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers()),
        Map.entry("NOTIFICATIONS_CONSUMER_ENABLED", "true"), Map.entry("WEBHOOKS_ENABLED", "true"), Map.entry("WEBHOOKS_ALLOW_INSECURE_LOCAL", "true"),
        Map.entry("WEBHOOK_SECRET_KEY", Base64.getEncoder().encodeToString(new byte[32])), Map.entry("WEBHOOK_RETRY_POLL_MS", "100"),
        Map.entry("WEBHOOK_RETRY_BASE_DELAY_MS", "100"), Map.entry("WEBHOOK_RETRY_MAX_DELAY_MS", "500"), Map.entry("SERVER_PORT", "18085")));
    gateway = launch("api-gateway", 18080, Map.of(
        "GATEWAY_PORT", "18080", "S1_URI", "http://127.0.0.1:18081", "S2_URI", "http://127.0.0.1:18082",
        "S5_URI", "http://127.0.0.1:18085", "JWT_ISSUER_URI", base,
        "JWT_JWKS_URI", base + "/protocol/openid-connect/certs", "JWT_AUDIENCE", "zentro-api"));
  }

  private static Process launch(String module, int port, Map<String, String> properties) throws Exception {
    Path jar = MODULE.getParent().resolve(module + "/target/" + module + "-0.1.0-SNAPSHOT.jar");
    if (!java.nio.file.Files.isRegularFile(jar)) throw new IllegalStateException("Expected packaged service jar: " + jar);
    ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xms96m", "-Xmx256m", "-jar", jar.toString());
    builder.environment().putAll(properties);
    builder.environment().put("SERVER_PORT", Integer.toString(port));
    builder.directory(MODULE.resolve("target").toFile());
    Process process = builder.redirectErrorStream(true)
        .redirectOutput(MODULE.resolve("target/e2e-" + module + ".log").toFile()).start();
    Awaitility.await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(250)).until(() -> {
      if (!process.isAlive()) throw new IllegalStateException(module + " exited during startup");
      try { return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
          HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
      catch (Exception ignored) { return false; }
    });
    return process;
  }

  private static void createTestClient() throws Exception {
    customerId = createServiceClient(CUSTOMER_CLIENT, CUSTOMER_SECRET, "CUSTOMER");
    createServiceClient(DISPATCHER_CLIENT, DISPATCHER_SECRET, "DISPATCHER");
  }

  private static String createServiceClient(String clientId, String secret, String role) throws Exception {
    String admin = adminToken();
    HttpResponse<String> response = adminCall("POST", "/admin/realms/zentro/clients",
        "{\"clientId\":\"" + clientId + "\",\"enabled\":true,\"protocol\":\"openid-connect\",\"publicClient\":false,\"secret\":\"" + secret + "\",\"serviceAccountsEnabled\":true,\"directAccessGrantsEnabled\":false,\"standardFlowEnabled\":false}");
    assertThat(response.statusCode()).isEqualTo(201);
    JsonNode clients = send("GET", keycloak() + "/admin/realms/zentro/clients?clientId=" + clientId, admin, null, 200);
    String internalId = clients.get(0).path("id").asText();
    send("POST", keycloak() + "/admin/realms/zentro/clients/" + internalId + "/protocol-mappers/models", admin,
        "{\"name\":\"E2E audience\",\"protocol\":\"openid-connect\",\"protocolMapper\":\"oidc-audience-mapper\",\"consentRequired\":false,\"config\":{\"included.custom.audience\":\"zentro-api\",\"id.token.claim\":\"false\",\"access.token.claim\":\"true\"}}", 201);
    JsonNode account = send("GET", keycloak() + "/admin/realms/zentro/clients/" + internalId + "/service-account-user", admin, null, 200);
    String id = account.path("id").asText();
    simulateRoleAssignment(admin, id, role);
    return id;
  }

  private static synchronized String adminToken() throws Exception {
    if (adminAccessToken != null && Instant.now().isBefore(adminAccessTokenExpiresAt)) return adminAccessToken;
    String form = "grant_type=password&client_id=admin-cli&username=" + encode(ADMIN_USER)
        + "&password=" + encode(ADMIN_PASSWORD);
    JsonNode token = JSON.readTree(send("POST", keycloak() + "/realms/master/protocol/openid-connect/token", null, form, 200,
        "application/x-www-form-urlencoded").body());
    adminAccessToken = token.path("access_token").asText();
    adminAccessTokenExpiresAt = Instant.now().plusSeconds(token.path("expires_in").asLong(60) - 5);
    return adminAccessToken;
  }

  private static String tokenForClient(String clientId, String secret) throws Exception {
    String form = "grant_type=client_credentials&client_id=" + encode(clientId) + "&client_secret=" + encode(secret);
    return JSON.readTree(send("POST", keycloak() + "/realms/zentro/protocol/openid-connect/token", null, form, 200,
        "application/x-www-form-urlencoded").body()).path("access_token").asText();
  }

  private static List<String> realmRoles(String token) throws Exception {
    JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    return JSON.convertValue(payload.path("realm_access").path("roles"), JSON.getTypeFactory().constructCollectionType(List.class, String.class));
  }

  private static HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
    return sendRaw(method, "http://127.0.0.1:18080" + path, token, body);
  }

  private static String orderBody() {
    return "{\"externalOrderRef\":\"e2e-" + UUID.randomUUID() + "\",\"sender\":{\"name\":\"Sender\",\"phone\":\"+94771234567\",\"address\":\"1 Long Road\",\"city\":\"Colombo\"},\"recipient\":{\"name\":\"Recipient\",\"phone\":\"+94771234568\",\"address\":\"2 Long Road\",\"city\":\"Colombo\"},\"parcel\":{\"weightKg\":1,\"category\":\"GENERAL\",\"description\":\"Test parcel\"},\"destinationType\":\"OTHER_DISTRICT\",\"isExpress\":false}";
  }

  private static String hmac(String secret, String timestamp, String body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    byte[] digest = mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
    return "sha256=" + HexFormat.of().formatHex(digest);
  }

  private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

  private static HttpResponse<String> adminCall(String method, String path, String body) throws Exception {
    return sendRaw(method, keycloak() + path, adminToken(), body);
  }

  private static JsonNode send(String method, String uri, String token, String body, int expected) throws Exception {
    HttpResponse<String> response = send(method, uri, token, body, expected, "application/json");
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }

  private static HttpResponse<String> send(String method, String uri, String token, String body, int expected, String contentType) throws Exception {
    HttpResponse<String> response = sendRaw(method, uri, token, body, contentType);
    assertThat(response.statusCode()).as("%s %s body=%s", method, uri, response.body()).isEqualTo(expected);
    return response;
  }

  private static HttpResponse<String> sendRaw(String method, String uri, String token, String body) throws Exception {
    return sendRaw(method, uri, token, body, "application/json");
  }

  private static HttpResponse<String> sendRaw(String method, String uri, String token, String body, String contentType) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(30))
        .header("Content-Type", contentType);
    if (token != null) builder.header("Authorization", "Bearer " + token);
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String keycloak() { return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080); }

  private static String jdbcUrl(String database) {
    return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
  }

  private static void initializeDatabases() throws Exception {
    createDatabase("identity_user", "identity-only", "identity_db");
    createDatabase("parcel_pricing_user", "parcel-pricing-only", "parcel_pricing_db");
    createDatabase("notification_integration_user", "notification-integration-only", "notification_integration_db");
  }

  private static void createDatabase(String user, String password, String database) throws Exception {
    assertThat(POSTGRES.execInContainer("psql", "-U", "e2e_admin", "-d", "e2e_admin", "-c",
        "CREATE USER " + user + " WITH PASSWORD '" + password + "'").getExitCode()).isZero();
    assertThat(POSTGRES.execInContainer("createdb", "-U", "e2e_admin", "-O", user, database).getExitCode()).isZero();
  }

  private static void stop(Process process) {
    if (process != null && process.isAlive()) {
      process.destroy();
      try { if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
    }
  }
}
