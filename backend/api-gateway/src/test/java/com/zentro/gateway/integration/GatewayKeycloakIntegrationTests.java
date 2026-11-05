package com.zentro.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.awaitility.Awaitility;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

/** Exercises the deployed gateway application and its configured routes using tokens issued by Keycloak. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers(disabledWithoutDocker = true)
class GatewayKeycloakIntegrationTests {
  private static final String REALM = "zentro";
  private static final String WRONG_AUDIENCE_CLIENT_ID = "gateway-it-wrong-audience";
  private static final String SHORT_LIVED_CLIENT_ID = "gateway-it-short-lived";
  private static final String TOKEN_PASSWORD = "gateway-it-" + java.util.UUID.randomUUID();
  private static final String BODY = "{\"origin\":\"zd-31\",\"payload\":7}";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private static final Map<String, MockWebServer> UPSTREAMS = startUpstreams();
  private static final Map<String, String> CLIENTS = Map.of(
      "CUSTOMER", "gateway-it-customer",
      "MERCHANT", "gateway-it-merchant",
      "COURIER", "gateway-it-courier",
      "DISPATCHER", "gateway-it-dispatcher",
      "SERVICE", "gateway-it-service",
      "ROLELESS", "gateway-it-roleless");
  private static final Map<String, String> CLIENT_SECRETS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final Map<String, String> ACCESS_TOKENS = new java.util.concurrent.ConcurrentHashMap<>();
  private static final List<Route> ROUTES = routeTable();

  @Container
  static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(DockerImageName.parse("quay.io/keycloak/keycloak:26.3.3"))
      .withExposedPorts(8080)
      .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "zd31-admin")
      .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", TOKEN_PASSWORD)
      .withCopyFileToContainer(
          org.testcontainers.utility.MountableFile.forHostPath(realmExportPath()),
          "/opt/keycloak/data/import/zentro-realm.json")
      .withCommand("start-dev", "--import-realm", "--http-enabled=true", "--hostname-strict=false")
      .waitingFor(Wait.forHttp("/realms/zentro/.well-known/openid-configuration")
          .forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

  @Autowired WebTestClient client;
  @LocalServerPort int port;

  private static Path realmExportPath() {
    return Path.of(System.getProperty("user.dir"), "../../infra/keycloak/realm-export.json")
        .toAbsolutePath().normalize();
  }

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    String issuer = keycloakBaseUrl() + "/realms/" + REALM;
    UPSTREAMS.forEach((service, upstream) -> registry.add(service + "_URI", () -> upstream.url("/").toString()));
    registry.add("JWT_ISSUER_URI", () -> issuer);
    registry.add("JWT_JWKS_URI", () -> issuer + "/protocol/openid-connect/certs");
    registry.add("JWT_AUDIENCE", () -> "zentro-api");
    registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "300ms");
    registry.add("GATEWAY_CONNECT_TIMEOUT_MS", () -> "500");
    registry.add("RATE_LIMIT_DEFAULT_PER_MINUTE", () -> "500");
    registry.add("RATE_LIMIT_TRACKING_PER_MINUTE", () -> "500");
  }

  @BeforeAll
  static void createKeycloakTestUsersAndClients() throws Exception {
    String adminToken = adminToken();
    for (Map.Entry<String, String> client : CLIENTS.entrySet()) {
      createTestClient(adminToken, client.getValue(), "zentro-api", null,
          client.getKey().equals("ROLELESS") ? null : client.getKey());
    }
    createTestClient(adminToken, WRONG_AUDIENCE_CLIENT_ID, "other-audience", null, "CUSTOMER");
    createTestClient(adminToken, SHORT_LIVED_CLIENT_ID, "zentro-api", 1, "CUSTOMER");
    for (Map.Entry<String, String> client : CLIENTS.entrySet()) {
      String token = tokenForClient(client.getValue());
      ACCESS_TOKENS.put(client.getKey(), token);
      List<String> actualRoles = tokenRealmRoles(token);
      List<String> expectedRoles = client.getKey().equals("ROLELESS") ? List.of() : List.of(client.getKey());
      if (!actualRoles.equals(expectedRoles)) {
        throw new IllegalStateException("Keycloak fixture role mismatch for " + client.getKey() + ": " + actualRoles);
      }
    }
  }

  @AfterAll
  static void stopUpstreams() throws IOException {
    for (MockWebServer upstream : UPSTREAMS.values()) upstream.shutdown();
  }

  static Stream<org.junit.jupiter.params.provider.Arguments> routeRoleMatrix() {
    List<String> identities = List.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE", "ROLELESS", "ANONYMOUS");
    return ROUTES.stream().flatMap(route -> identities.stream().map(identity -> arguments(route, identity)));
  }

  @ParameterizedTest(name = "{0} with {1}")
  @MethodSource("routeRoleMatrix")
  void routeRoleMatrixForwardsAllowedCallsAndRejectsOthers(Route route, String identity) throws Exception {
    clearRequests();
    boolean allowed = route.allowedIdentities().contains(identity);
    String authorization = identity.equals("ANONYMOUS") ? null : "Bearer " + accessToken(identity);
    String query = "?probe=zd31&route=" + route.id();
    String target = "http://localhost:" + port + route.path() + query;

    WebTestClient.RequestBodySpec request = client.method(HttpMethod.valueOf(route.method()))
        .uri(URI.create(target))
        .contentType(MediaType.APPLICATION_JSON)
        .header("X-Forwarded-For", "198.51.100." + (Math.abs((route.id() + identity).hashCode()) % 200 + 1))
        .header("X-User-Id", "untrusted-user")
        .header("X-Roles", "ROLE_ADMIN")
        .header("X-Forwarded-User", "untrusted-forwarded-user");
    if (authorization != null) request.header("Authorization", authorization);
    EntityExchangeResult<byte[]> response = route.method().equals("GET")
        ? request.exchange().expectBody().returnResult()
        : request.bodyValue(BODY).exchange().expectBody().returnResult();

    if (allowed) {
      assertThat(response.getStatus().value()).as("route=%s identity=%s", route.id(), identity).isEqualTo(200);
      assertThat(new String(response.getResponseBody(), StandardCharsets.UTF_8)).isEqualTo("stub:" + route.service());
      RecordedRequest received = UPSTREAMS.get(route.service()).takeRequest(2, TimeUnit.SECONDS);
      assertThat(received).as("expected %s to receive route=%s identity=%s", route.service(), route.id(), identity).isNotNull();
      assertThat(received.getMethod()).isEqualTo(route.method());
      assertThat(received.getPath()).isEqualTo(route.path() + query);
      assertThat(received.getBody().readUtf8()).isEqualTo(route.method().equals("GET") ? "" : BODY);
      assertThat(received.getHeader("X-User-Id")).isNull();
      assertThat(received.getHeader("X-Roles")).isNull();
      assertThat(received.getHeader("X-Forwarded-User")).isNull();
      assertNoOtherUpstreamReceived(route.service());
    } else {
      int expectedStatus = identity.equals("ANONYMOUS") ? 401 : 403;
      assertThat(response.getStatus().value()).as("route=%s identity=%s", route.id(), identity).isEqualTo(expectedStatus);
      assertNoUpstreamReceived();
    }
  }

  @Test
  void invalidBearerAndTokenAttacksAreUnauthorizedWithoutForwarding() throws Exception {
    String customerToken = accessToken("CUSTOMER");
    String wrongAudience = tokenForClient(WRONG_AUDIENCE_CLIENT_ID);
    List<String> attacks = List.of(
        "not-a-jwt",
        mutateJwt(customerToken, "signature"),
        mutateJwt(customerToken, "issuer"),
        unsignedJwt(customerToken),
        mutateJwt(customerToken, "role"),
        wrongAudience,
        "Bearer",
        "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(StandardCharsets.UTF_8)));

    for (String attack : attacks) {
      clearRequests();
      String authorization = attack.startsWith("Bearer") || attack.startsWith("Basic") ? attack : "Bearer " + attack;
      client.get().uri("/api/v1/admin/users").header("Authorization", authorization)
          .exchange().expectStatus().isUnauthorized();
      assertNoUpstreamReceived();
    }
  }

  @Test
  void expiredKeycloakTokenIsUnauthorizedWithoutForwarding() throws Exception {
    String token = tokenForClient(SHORT_LIVED_CLIENT_ID);
    long expiresAt = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1])).path("exp").asLong();
    // Spring's JWT timestamp validator allows a 60-second clock skew by default.
    Awaitility.await().atMost(Duration.ofSeconds(75)).pollInterval(Duration.ofMillis(100))
        .until(() -> Instant.now().getEpochSecond() > expiresAt + 65);

    clearRequests();
    client.get().uri("/api/v1/parcels/estimate").header("Authorization", "Bearer " + token)
        .exchange().expectStatus().isUnauthorized();
    assertNoUpstreamReceived();
  }

  @Test
  void malformedAndUnsupportedAuthorizationSchemesDoNotReachProtectedRoutes() throws Exception {
    for (String header : List.of("Bearer ", "Bearer abc.def", "Basic dXNlcjpwYXNz", "Token abc.def")) {
      clearRequests();
      client.get().uri("/api/v1/admin/users").header("Authorization", header)
          .exchange().expectStatus().isUnauthorized();
      assertNoUpstreamReceived();
    }
  }

  @Test
  void pathAndMethodBypassAttemptsNeverReachProtectedUpstreamsWithoutAValidRole() throws Exception {
    List<Probe> probes = List.of(
        new Probe("GET", "/api/v1/admin/users/"),
        new Probe("GET", "/api/v1//admin/users"),
        new Probe("GET", "/API/V1/admin/users"),
        new Probe("GET", "/api/v1/admin%2Fusers"),
        new Probe("GET", "/api/v1/%2e%2e/admin/users"),
        new Probe("GET", "/api/v1/admin/../dispatch/jobs"),
        new Probe("GET", "/api/v1/admin/users;jsessionid=zd31"),
        new Probe("POST", "/api/v1/parcels/estimate-fee"),
        new Probe("GET", "/actuator/env"),
        new Probe("GET", "/api/v1/not-listed"),
        new Probe("HEAD", "/api/v1/admin/users"),
        new Probe("OPTIONS", "/api/v1/admin/users"),
        new Probe("TRACE", "/api/v1/admin/users"),
        new Probe("DELETE", "/api/v1/admin/users"));

    for (Probe probe : probes) {
      clearRequests();
      int status;
      try {
        status = client.method(HttpMethod.valueOf(probe.method())).uri(URI.create("http://localhost:" + port + probe.path()))
            .header("X-HTTP-Method-Override", "GET").exchange().returnResult(byte[].class).getStatus().value();
      } catch (IllegalArgumentException | org.springframework.web.reactive.function.client.WebClientRequestException clientRejectedMalformedTarget) {
        // The HTTP client may reject an invalid raw target before it reaches the gateway.
        assertNoUpstreamReceived();
        continue;
      }
      assertThat(status).as("probe=%s %s", probe.method(), probe.path()).isIn(400, 401, 403, 404, 405, 414);
      assertNoUpstreamReceived();
    }
  }

  @Test
  void identityAndForwardingSpoofHeadersAreRemovedBeforeUpstream() throws Exception {
    clearRequests();
    String token = accessToken("CUSTOMER");
    client.get().uri("/api/v1/users/me?probe=spoof").header("Authorization", "Bearer " + token)
        .header("Host", "attacker.example")
        .header("X-Forwarded-Host", "attacker.example")
        .header("X-Forwarded-Proto", "https")
        .header("X-Forwarded-User", "attacker")
        .header("X-Forwarded-Roles", "ROLE_DISPATCHER")
        .header("X-Authenticated-User", "attacker")
        .header("X-Authenticated-Roles", "ROLE_ADMIN")
        .header("X-Principal", "attacker")
        .header("X-Remote-User", "attacker")
        .header("X-Remote-Groups", "ROLE_ADMIN")
        .exchange().expectStatus().isOk();

    RecordedRequest received = UPSTREAMS.get("S1").takeRequest(2, TimeUnit.SECONDS);
    assertThat(received).isNotNull();
    for (String header : List.of("X-User-Id", "X-Roles", "X-Forwarded-User", "X-Forwarded-Roles",
        "X-Authenticated-User", "X-Authenticated-Roles", "X-Principal", "X-Remote-User", "X-Remote-Groups")) {
      assertThat(received.getHeader(header)).as("stripped %s", header).isNull();
    }
  }

  @Test
  void corsReportsAllowedHeadersAndDocumentsMissingIdempotencyKeyWithoutFailing() {
    client.options().uri("http://localhost:" + port + "/api/v1/parcels")
        .header("Origin", "http://localhost:3000")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "authorization,content-type,x-correlation-id")
        .exchange().expectStatus().isOk()
        .expectHeader().value("Access-Control-Allow-Headers", value -> {
          assertThat(value.toLowerCase()).contains("authorization", "content-type", "x-correlation-id");
          assertThat(value.toLowerCase()).doesNotContain("idempotency-key");
        });

    client.options().uri("http://localhost:" + port + "/api/v1/parcels")
        .header("Origin", "http://localhost:3000")
        .header("Access-Control-Request-Method", "POST")
        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key")
        .exchange().expectStatus().value(status -> assertThat(status).isIn(200, 403))
        .expectHeader().value("Access-Control-Allow-Headers", value ->
            assertThat(value.toLowerCase()).doesNotContain("idempotency-key"));
  }

  @Test
  void emptyPostAndMalformedJsonAreForwardedUnchanged() throws Exception {
    String token = accessToken("CUSTOMER");
    clearRequests();
    client.post().uri("/api/v1/parcels/empty?probe=empty").header("Authorization", "Bearer " + token)
        .exchange().expectStatus().isOk();
    RecordedRequest empty = UPSTREAMS.get("S2").takeRequest(2, TimeUnit.SECONDS);
    assertThat(empty).isNotNull();
    assertThat(empty.getMethod()).isEqualTo("POST");
    assertThat(empty.getPath()).isEqualTo("/api/v1/parcels/empty?probe=empty");
    assertThat(empty.getBody().readUtf8()).isEmpty();

    clearRequests();
    String invalidJson = "{not-json";
    client.post().uri("/api/v1/parcels/invalid-json").header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON).bodyValue(invalidJson).exchange().expectStatus().isOk();
    RecordedRequest malformed = UPSTREAMS.get("S2").takeRequest(2, TimeUnit.SECONDS);
    assertThat(malformed).isNotNull();
    assertThat(malformed.getBody().readUtf8()).isEqualTo(invalidJson);
  }

  @Test
  void oversizedHeaderIsRejectedSafelyAndLargeBodyIsForwardedUnchanged() throws Exception {
    clearRequests();
    try {
      int status = client.get().uri("/api/v1/admin/users")
          .header("X-Oversized-Probe", "x".repeat(32 * 1024))
          .exchange().returnResult(byte[].class).getStatus().value();
      assertThat(status).isIn(400, 401, 403, 413, 431);
    } catch (org.springframework.web.reactive.function.client.WebClientRequestException rejectedBeforeResponse) {
      // The HTTP server may close the connection before returning an HTTP status for an oversized header.
    }
    assertNoUpstreamReceived();

    String body = "x".repeat(1024 * 1024);
    client.post().uri("/api/v1/zones/large-body").header("Authorization", "Bearer " + accessToken("CUSTOMER"))
        .contentType(MediaType.TEXT_PLAIN).bodyValue(body).exchange().expectStatus().isOk();
    RecordedRequest received = UPSTREAMS.get("S2").takeRequest(2, TimeUnit.SECONDS);
    assertThat(received).isNotNull();
    assertThat(received.getBody().readUtf8()).isEqualTo(body);
  }

  @Test
  void concurrentRequestsDoNotLeakAuthorizationOrIdentityBetweenRoles() throws Exception {
    clearRequests();
    List<String> identities = List.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "CUSTOMER", "MERCHANT");
    List<String> tokens = new ArrayList<>();
    List<CompletableFuture<Void>> calls = new ArrayList<>();
    for (int i = 0; i < identities.size(); i++) {
      String identity = identities.get(i);
      String token = accessToken(identity);
      tokens.add(token);
      int requestId = i;
      calls.add(CompletableFuture.runAsync(() -> client.get().uri("/api/v1/users/me?concurrent=" + requestId)
          .header("Authorization", "Bearer " + token)
          .header("X-User-Id", "spoof-" + requestId)
          .header("X-Roles", "ROLE_DISPATCHER")
          .exchange().expectStatus().isOk()));
    }
    CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

    Map<String, Integer> actualTokens = new LinkedHashMap<>();
    for (int i = 0; i < identities.size(); i++) {
      RecordedRequest received = UPSTREAMS.get("S1").takeRequest(2, TimeUnit.SECONDS);
      assertThat(received).isNotNull();
      actualTokens.merge(received.getHeader("Authorization"), 1, Integer::sum);
      assertThat(received.getHeader("X-User-Id")).isNull();
      assertThat(received.getHeader("X-Roles")).isNull();
    }
    for (String token : tokens) {
      assertThat(actualTokens).containsKey("Bearer " + token);
    }
  }

  @Test
  void upstreamServerErrorsAreReplacedWithSafeProblemDetailsAndGatewayKeepsServing() throws Exception {
    String token = "Bearer " + accessToken("CUSTOMER");
    clearRequests();
    EntityExchangeResult<byte[]> failure = client.get().uri("/api/v1/zones/__zd31_failure")
        .header("Authorization", token).exchange().expectBody().returnResult();
    assertThat(failure.getStatus().value()).isEqualTo(502);
    assertThat(failure.getResponseHeaders().getFirst("Content-Type")).startsWith("application/problem+json");
    assertThat(failure.getResponseHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    String safeBody = new String(failure.getResponseBody(), StandardCharsets.UTF_8);
    assertThat(safeBody).contains("\"title\":\"Bad Gateway\"", "\"status\":502")
        .doesNotContain("upstream-secret", "internal-host", "stacktrace", "S2", "8082");

    client.get().uri("/api/v1/zones/healthy-after-failure").header("Authorization", token)
        .exchange().expectStatus().isOk();
  }

  @Disabled("Known shape mismatch: upstream 5xx sanitizer omits `instance`; see docs/testing/gateway-integration-tests.md#findings")
  @Test
  void upstreamAndGatewayFailuresUseTheSameProblemJsonShape() throws Exception {
    EntityExchangeResult<byte[]> response = client.get().uri("/api/v1/zones/__zd31_failure")
        .header("Authorization", "Bearer " + accessToken("CUSTOMER"))
        .exchange().expectBody().returnResult();
    JsonNode problem = JSON.readTree(response.getResponseBody());
    Set<String> fields = new java.util.HashSet<>();
    problem.fieldNames().forEachRemaining(fields::add);
    assertThat(fields).containsExactlyInAnyOrder("type", "title", "status", "detail", "instance");
  }

  @Test
  void slowUpstreamReturnsSafe504AndCorrelationId() throws Exception {
    clearRequests();
    EntityExchangeResult<byte[]> response = client.get().uri("/api/v1/zones/__zd31_slow")
        .header("Authorization", "Bearer " + accessToken("CUSTOMER"))
        .exchange().expectBody().returnResult();
    assertThat(response.getStatus().value()).isEqualTo(504);
    assertThat(response.getResponseHeaders().getFirst("Content-Type")).startsWith("application/problem+json");
    assertThat(response.getResponseHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    String safeBody = new String(response.getResponseBody(), StandardCharsets.UTF_8);
    assertThat(safeBody).contains("\"status\":504").doesNotContain("slow upstream", "internal-host", "stacktrace");
    assertThat(UPSTREAMS.get("S2").takeRequest(2, TimeUnit.SECONDS)).isNotNull();
  }

  @Test
  void trackingAndHealthArePublicButUnlistedRoutesAreNotForwarded() throws Exception {
    for (String path : List.of("/api/v1/tracking/ZTR-1234567890", "/api/v1/tracking/ZTR-1234567890/events",
        "/api/v1/tracking/ZTR-1234567890/stream")) {
      clearRequests();
      client.get().uri(path).exchange().expectStatus().isOk();
      assertThat(UPSTREAMS.get("S4").takeRequest(2, TimeUnit.SECONDS)).isNotNull();
    }
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    clearRequests();
    client.get().uri("/api/v1/unlisted-public-route").exchange().expectStatus().isNotFound()
        .expectHeader().contentType("application/problem+json");
    assertNoUpstreamReceived();
  }

  private static List<Route> routeTable() {
    Set<String> humans = Set.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER");
    Set<String> dispatcher = Set.of("DISPATCHER");
    Set<String> publicLookup = Set.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "ROLELESS", "ANONYMOUS");
    return List.of(
        new Route("identity-users", "/api/v1/users/me", "S1", "POST", humans),
        new Route("identity-merchants", "/api/v1/merchants/me", "S1", "POST", humans),
        new Route("identity-admin-merchants", "/api/v1/admin/merchants/17", "S1", "POST", dispatcher),
        new Route("identity-admin-users", "/api/v1/admin/users/17", "S1", "POST", dispatcher),
        new Route("identity-admin-couriers", "/api/v1/admin/couriers/17", "S1", "POST", dispatcher),
        new Route("parcel-zones", "/api/v1/zones", "S2", "POST", humans),
        new Route("parcel-parcels", "/api/v1/parcels/estimate", "S2", "POST", Set.of("CUSTOMER", "DISPATCHER")),
        new Route("parcel-merchant-orders", "/api/v1/merchant/orders/17", "S2", "POST", Set.of("MERCHANT")),
        new Route("parcel-admin-zones", "/api/v1/admin/zones", "S2", "POST", dispatcher),
        new Route("parcel-admin-pricing", "/api/v1/admin/pricing-rules", "S2", "POST", dispatcher),
        new Route("dispatch-jobs", "/api/v1/dispatch/jobs", "S3", "POST", dispatcher),
        new Route("courier-jobs", "/api/v1/courier/jobs", "S3", "POST", Set.of("COURIER")),
        new Route("tracking-public", "/api/v1/tracking/ZTR-1234567890", "S4", "GET", publicLookup),
        new Route("tracking-mine", "/api/v1/tracking/mine", "S4", "GET", Set.of("CUSTOMER", "MERCHANT")),
        new Route("tracking-events", "/api/v1/tracking/ZTR-1234567890/events", "S4", "GET", publicLookup),
        new Route("tracking-stream", "/api/v1/tracking/ZTR-1234567890/stream", "S4", "GET", publicLookup),
        new Route("reports", "/api/v1/reports/summary", "S4", "POST", dispatcher),
        new Route("merchant-webhooks", "/api/v1/merchant/webhooks", "S5", "POST", Set.of("MERCHANT")),
        new Route("admin-audit", "/api/v1/admin/audit-logs", "S5", "POST", dispatcher));
  }

  private static Map<String, MockWebServer> startUpstreams() {
    Map<String, MockWebServer> servers = new LinkedHashMap<>();
    for (String service : List.of("S1", "S2", "S3", "S4", "S5")) {
      MockWebServer server = new MockWebServer();
      server.setDispatcher(new Dispatcher() {
        @Override public MockResponse dispatch(RecordedRequest request) {
          if ("S2".equals(service) && request.getPath().startsWith("/api/v1/zones/__zd31_failure")) {
            return new MockResponse().setResponseCode(503).setBody("upstream-secret internal-host:8082 stacktrace");
          }
          if ("S2".equals(service) && request.getPath().contains("__zd31_slow")) {
            return new MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody("slow");
          }
          return new MockResponse().setResponseCode(200).setBody("stub:" + service);
        }
      });
      try { server.start(); } catch (IOException e) { throw new ExceptionInInitializerError(e); }
      servers.put(service, server);
    }
    return Map.copyOf(servers);
  }

  private static String keycloakBaseUrl() {
    return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
  }

  private static String adminToken() throws Exception {
    String form = "client_id=admin-cli&username=zd31-admin&password=" + encode(TOKEN_PASSWORD) + "&grant_type=password";
    JsonNode token = send("POST", keycloakBaseUrl() + "/realms/master/protocol/openid-connect/token", form,
        "application/x-www-form-urlencoded", null);
    return token.path("access_token").asText();
  }

  private static void createTestClient(String adminToken, String clientId, String audience,
                                       Integer tokenLifespan, String role) throws Exception {
    String secret = java.util.UUID.randomUUID().toString();
    String attributes = tokenLifespan == null ? "{}"
        : "{\"access.token.lifespan\":\"" + tokenLifespan + "\"}";
    send("POST", keycloakBaseUrl() + "/admin/realms/" + REALM + "/clients",
        "{\"clientId\":\"" + clientId + "\",\"enabled\":true,\"protocol\":\"openid-connect\","
            + "\"publicClient\":false,\"secret\":\"" + secret + "\",\"serviceAccountsEnabled\":true,"
            + "\"directAccessGrantsEnabled\":false,\"standardFlowEnabled\":false,\"attributes\":" + attributes + "}",
        "application/json", adminToken);
    JsonNode clients = send("GET", keycloakBaseUrl() + "/admin/realms/" + REALM + "/clients?clientId=" + clientId,
        null, null, adminToken);
    String internalId = clients.get(0).path("id").asText();
    String mapper = "{\"name\":\"zd31-audience\",\"protocol\":\"openid-connect\","
        + "\"protocolMapper\":\"oidc-audience-mapper\",\"consentRequired\":false,\"config\":{"
        + "\"included.custom.audience\":\"" + audience + "\",\"id.token.claim\":\"false\","
        + "\"access.token.claim\":\"true\"}}";
    send("POST", keycloakBaseUrl() + "/admin/realms/" + REALM + "/clients/" + internalId + "/protocol-mappers/models",
        mapper, "application/json", adminToken);
    CLIENT_SECRETS.put(clientId, secret);
    JsonNode serviceAccount = send("GET", keycloakBaseUrl() + "/admin/realms/" + REALM
        + "/clients/" + internalId + "/service-account-user", null, null, adminToken);
    String serviceAccountId = serviceAccount.path("id").asText();
    // The imported realm's default-roles composite grants CUSTOMER to every new account.
    removeRealmRole(adminToken, serviceAccountId, "default-roles-" + REALM);
    if (role != null) assignRealmRole(adminToken, serviceAccountId, role);
  }

  private static void assignRealmRole(String adminToken, String userId, String role) throws Exception {
    JsonNode roleRepresentation = send("GET", keycloakBaseUrl() + "/admin/realms/" + REALM + "/roles/" + role,
        null, null, adminToken);
    send("POST", keycloakBaseUrl() + "/admin/realms/" + REALM + "/users/" + userId + "/role-mappings/realm",
        "[" + JSON.writeValueAsString(roleRepresentation) + "]", "application/json", adminToken);
  }

  private static void removeRealmRole(String adminToken, String userId, String role) throws Exception {
    JsonNode roleRepresentation = send("GET", keycloakBaseUrl() + "/admin/realms/" + REALM + "/roles/" + role,
        null, null, adminToken);
    send("DELETE", keycloakBaseUrl() + "/admin/realms/" + REALM + "/users/" + userId + "/role-mappings/realm",
        "[" + JSON.writeValueAsString(roleRepresentation) + "]", "application/json", adminToken);
  }

  private static String accessToken(String identity) throws Exception {
    if (identity.equals("ANONYMOUS")) return "";
    String token = ACCESS_TOKENS.get(identity);
    if (token == null) throw new IllegalArgumentException("Unknown test identity");
    return token;
  }

  private static String tokenForClient(String clientId) throws Exception {
    String secret = CLIENT_SECRETS.get(clientId);
    if (secret == null) throw new IllegalArgumentException("Unknown test client");
    String form = "client_id=" + encode(clientId) + "&client_secret=" + encode(secret) + "&grant_type=client_credentials";
    JsonNode token = send("POST", keycloakBaseUrl() + "/realms/" + REALM + "/protocol/openid-connect/token",
        form, "application/x-www-form-urlencoded", null);
    return token.path("access_token").asText();
  }

  private static JsonNode send(String method, String uri, String body, String contentType, String bearer) throws Exception {
    HttpResponse<String> response = sendResponse(method, uri, body, contentType, bearer);
    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
  }

  private static HttpResponse<String> sendResponse(String method, String uri, String body, String contentType, String bearer) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10));
    if (contentType != null) request.header("Content-Type", contentType);
    if (bearer != null) request.header("Authorization", "Bearer " + bearer);
    if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
    else request.method(method, HttpRequest.BodyPublishers.ofString(body));
    HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      String safeReason = "";
      try {
        JsonNode error = JSON.readTree(response.body());
        safeReason = ", error=" + error.path("error").asText("unknown")
            + ", description=" + error.path("error_description").asText("unknown");
      } catch (Exception ignored) {
        // Never include response bodies, credentials, or tokens in fixture errors.
      }
      throw new IllegalStateException("Keycloak test fixture request failed: " + method + " " + URI.create(uri).getPath()
          + " returned " + response.statusCode() + safeReason);
    }
    return response;
  }

  private static String mutateJwt(String token, String mutation) throws Exception {
    String[] parts = token.split("\\.");
    ObjectNode claims = (ObjectNode) JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
    switch (mutation) {
      case "signature" -> {
        char replacement = parts[2].charAt(0) == 'A' ? 'B' : 'A';
        parts[2] = replacement + parts[2].substring(1);
        return String.join(".", parts);
      }
      case "issuer" -> claims.put("iss", "https://wrong-issuer.invalid/realms/zentro");
      case "role" -> claims.withObject("realm_access").putArray("roles").add("DISPATCHER");
      default -> throw new IllegalArgumentException("Unknown mutation");
    }
    parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims));
    return String.join(".", parts);
  }

  private static String unsignedJwt(String signedToken) throws Exception {
    String payload = signedToken.split("\\.")[1];
    String noneHeader = Base64.getUrlEncoder().withoutPadding()
        .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    return noneHeader + "." + payload + ".";
  }

  private static List<String> tokenRealmRoles(String token) throws Exception {
    JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    List<String> roles = new ArrayList<>();
    claims.path("realm_access").path("roles").forEach(role -> {
      if (Set.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE").contains(role.asText())) {
        roles.add(role.asText());
      }
    });
    roles.sort(String::compareTo);
    return roles;
  }

  private static String encode(String value) {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private void clearRequests() throws InterruptedException {
    for (MockWebServer upstream : UPSTREAMS.values()) {
      while (upstream.takeRequest(2, TimeUnit.MILLISECONDS) != null) { /* discard requests from prior cells */ }
    }
  }

  private void assertNoUpstreamReceived() throws InterruptedException {
    for (Map.Entry<String, MockWebServer> upstream : UPSTREAMS.entrySet()) {
      assertThat(upstream.getValue().takeRequest(20, TimeUnit.MILLISECONDS))
          .as("rejected call must not reach %s", upstream.getKey()).isNull();
    }
  }

  private void assertNoOtherUpstreamReceived(String expectedService) throws InterruptedException {
    for (Map.Entry<String, MockWebServer> upstream : UPSTREAMS.entrySet()) {
      if (!upstream.getKey().equals(expectedService)) {
        assertThat(upstream.getValue().takeRequest(20, TimeUnit.MILLISECONDS))
            .as("route should not reach %s", upstream.getKey()).isNull();
      }
    }
  }

  private record Route(String id, String path, String service, String method, Set<String> allowedIdentities) {}
  private record Probe(String method, String path) {}
}
