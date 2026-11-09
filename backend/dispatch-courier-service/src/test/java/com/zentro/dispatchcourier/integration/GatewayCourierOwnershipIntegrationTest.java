package com.zentro.dispatchcourier.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;

/** Real gateway, Keycloak JWT validation at both layers, S3 and its PostgreSQL. S1 is a double.
 * Package api-gateway first, as the full reactor test/package does. Reuses the existing gateway
 * disposable client fixture approach and E2E process launcher; no production configuration changes. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"zentro.pod.worker-enabled=false", "debug=false"})
class GatewayCourierOwnershipIntegrationTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  static final String PASSWORD = UUID.randomUUID().toString();
  static final String ROOT = "/api/v1/courier/deliveries";
  static final String EVIDENCE = "{\"recipientName\":\"Synthetic Recipient\",\"proofReference\":\"SYNTHETIC-1\"}";
  @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
  @Container static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.3.3")
      .withExposedPorts(8080).withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "ownership-admin")
      .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", PASSWORD)
      .withCopyFileToContainer(MountableFile.forHostPath(Path.of("../../infra/keycloak/realm-export.json")),
          "/opt/keycloak/data/import/zentro-realm.json")
      .withCommand("start-dev", "--import-realm", "--hostname-strict=false")
      .waitingFor(Wait.forHttp("/realms/zentro/.well-known/openid-configuration")
          .withStartupTimeout(Duration.ofMinutes(2)));
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    r.add("spring.datasource.username", POSTGRES::getUsername);
    r.add("spring.datasource.password", POSTGRES::getPassword);
    r.add("zentro.security.issuer-uri", () -> base() + "/realms/zentro");
    r.add("zentro.security.jwks-uri", () -> base() + "/realms/zentro/protocol/openid-connect/certs");
    r.add("zentro.security.audience", () -> "zentro-api");
  }
  @LocalServerPort int s3Port;
  @Autowired JdbcTemplate jdbc;
  @MockBean IdentityCourierClient identity;
  static Process gateway;
  int gatewayPort;
  final Map<String, String> tokens = new HashMap<>();
  final UUID parcelA = UUID.randomUUID(), parcelB = UUID.randomUUID(), unassigned = UUID.randomUUID();

  @BeforeEach void setup() throws Exception {
    String admin = fixture("POST", "/realms/master/protocol/openid-connect/token",
        "client_id=admin-cli&username=ownership-admin&password=" + PASSWORD + "&grant_type=password", null, true)
        .path("access_token").asText();
    for (String name : List.of("a", "b", "DISPATCHER", "CUSTOMER", "MERCHANT", "SERVICE"))
      tokens.put(name, createClient(admin, name, name.length() == 1 ? "COURIER" : name));
    jdbc.execute("TRUNCATE dispatch.proof_of_delivery, dispatch.delivery_assignment");
    for (String name : List.of("a", "b")) {
      var courier = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "Synthetic Courier", true, "ACTIVE");
      when(identity.currentCourier(tokens.get(name))).thenReturn(courier);
      jdbc.update("INSERT INTO dispatch.delivery_assignment (parcel_id,courier_profile_id,courier_user_id,assigned_by_subject) VALUES (?,?,?,?)",
          name.equals("a") ? parcelA : parcelB, courier.id(), courier.userId(), "synthetic-dispatcher");
    }
    try (var socket = new ServerSocket(0)) { gatewayPort = socket.getLocalPort(); }
    Path jar = Path.of("../api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    assertThat(jar).isRegularFile();
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(),
        "-Xms96m", "-Xmx256m", "-jar", jar.toString());
    builder.environment().putAll(Map.of("GATEWAY_PORT", Integer.toString(gatewayPort),
        "S3_URI", "http://127.0.0.1:" + s3Port, "JWT_ISSUER_URI", base() + "/realms/zentro",
        "JWT_JWKS_URI", base() + "/realms/zentro/protocol/openid-connect/certs", "JWT_AUDIENCE", "zentro-api"));
    gateway = builder.directory(Path.of("target").toFile()).redirectErrorStream(true)
        .redirectOutput(Path.of("target/gateway-courier-ownership.log").toFile()).start();
    long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
    while (true) {
      assertThat(gateway.isAlive()).as("gateway startup; see target/gateway-courier-ownership.log").isTrue();
      try { if (request("GET", "/actuator/health", null, null).statusCode() == 200) break; }
      catch (java.io.IOException ignored) { }
      assertThat(System.nanoTime()).as("gateway startup timeout").isLessThan(deadline);
      Thread.sleep(200);
    }
  }
  @AfterEach void stopGateway() throws Exception {
    if (gateway != null) { gateway.destroy(); if (!gateway.waitFor(10, TimeUnit.SECONDS)) gateway.destroyForcibly(); }
  }

  @Test void gatewayPreservesOwnershipAndDeniedOperationsLeaveS3DatabaseUnchanged() throws Exception {
    var before = jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment ORDER BY parcel_id");
    for (String name : List.of("a", "b")) {
      var list = request("GET", ROOT, name, null);
      assertThat(list.statusCode()).isEqualTo(200);
      assertThat(list.body()).contains((name.equals("a") ? parcelA : parcelB).toString())
          .doesNotContain((name.equals("a") ? parcelB : parcelA).toString(), unassigned.toString());
    }
    assertThat(request("POST", ROOT + "/" + parcelB + "/proof", "b", EVIDENCE).statusCode()).isEqualTo(201);
    assertThat(request("GET", ROOT + "/" + parcelB, "b", null).statusCode()).isEqualTo(200);
    var ownProof = request("GET", ROOT + "/" + parcelB + "/proof", "b", null);
    assertThat(ownProof.statusCode()).isEqualTo(200);
    assertThat(ownProof.headers().firstValue("Cache-Control")).contains("no-store");
    assertThat(request("GET", ROOT + "/" + parcelA, "b", null).statusCode()).isEqualTo(403);
    var proofBefore = jdbc.queryForList("SELECT * FROM dispatch.proof_of_delivery ORDER BY parcel_id");
    for (UUID id : List.of(parcelB, unassigned)) {
      int status = id.equals(parcelB) ? 403 : 404;
      for (String suffix : List.of("", "/proof")) {
        var denied = request("GET", ROOT + "/" + id + suffix, "a", null);
        assertThat(denied.statusCode()).isEqualTo(status);
        assertThat(denied.body()).doesNotContain("Synthetic Recipient", "SYNTHETIC-1");
      }
      assertThat(request("POST", ROOT + "/" + id + "/proof", "a", EVIDENCE).statusCode()).isEqualTo(status);
    }
    for (String role : Arrays.asList("DISPATCHER", "CUSTOMER", "MERCHANT", "SERVICE", null)) {
      int status = role == null ? 401 : 403;
      for (String suffix : List.of("", "/" + parcelA, "/" + parcelA + "/proof"))
        assertThat(request("GET", ROOT + suffix, role, null).statusCode()).isEqualTo(status);
      assertThat(request("POST", ROOT + "/" + parcelA + "/proof", role, EVIDENCE).statusCode()).isEqualTo(status);
    }
    assertThat(request("PATCH", "/api/v1/parcels/" + parcelA + "/status", "a", "{\"status\":\"DELIVERED\"}").statusCode()).isEqualTo(403);
    assertThat(request("GET", "/api/v1/dispatch/queue", "a", null).statusCode()).isEqualTo(403);
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment ORDER BY parcel_id")).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.proof_of_delivery ORDER BY parcel_id")).isEqualTo(proofBefore);
    // Courier progress/failure endpoints do not exist; no invented request contract.
  }
  HttpResponse<String> request(String method, String path, String user, String body) throws Exception {
    var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json");
    if (user != null) b.header("Authorization", "Bearer " + tokens.get(user));
    return HTTP.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
  }
  static String base() { return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080); }
  static JsonNode fixture(String method, String path, String body, String bearer, boolean form) throws Exception {
    var b = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15))
        .header("Content-Type", form ? "application/x-www-form-urlencoded" : "application/json");
    if (bearer != null) b.header("Authorization", "Bearer " + bearer);
    var response = HTTP.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as("Keycloak fixture %s %s (body redacted)", method, path).isBetween(200, 299);
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }
  static String createClient(String admin, String name, String role) throws Exception {
    String id = "ownership-" + name, secret = UUID.randomUUID().toString(), root = "/admin/realms/zentro";
    fixture("POST", root + "/clients", JSON.writeValueAsString(Map.of("clientId", id, "enabled", true,
        "publicClient", false, "secret", secret, "serviceAccountsEnabled", true, "standardFlowEnabled", false)), admin, false);
    String internal = fixture("GET", root + "/clients?clientId=" + id, null, admin, false).get(0).path("id").asText();
    fixture("POST", root + "/clients/" + internal + "/protocol-mappers/models", JSON.writeValueAsString(Map.of(
        "name", "ownership-audience", "protocol", "openid-connect", "protocolMapper", "oidc-audience-mapper",
        "config", Map.of("included.custom.audience", "zentro-api", "access.token.claim", "true"))), admin, false);
    String account = fixture("GET", root + "/clients/" + internal + "/service-account-user", null, admin, false).path("id").asText();
    var defaults = fixture("GET", root + "/roles/default-roles-zentro", null, admin, false);
    fixture("DELETE", root + "/users/" + account + "/role-mappings/realm", "[" + defaults + "]", admin, false);
    var representation = fixture("GET", root + "/roles/" + role, null, admin, false);
    fixture("POST", root + "/users/" + account + "/role-mappings/realm", "[" + representation + "]", admin, false);
    return fixture("POST", "/realms/zentro/protocol/openid-connect/token", "client_id=" + id + "&client_secret=" + secret + "&grant_type=client_credentials", null, true).path("access_token").asText();
  }
}
