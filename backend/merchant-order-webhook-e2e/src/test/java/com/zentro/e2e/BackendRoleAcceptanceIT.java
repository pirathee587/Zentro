package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Signed Keycloak tokens; gateway and direct service must independently enforce approved roles. */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class BackendRoleAcceptanceIT extends BackendAcceptanceStack {
  private static final Map<String, String> TOKENS = new LinkedHashMap<>();
  private static String bookedParcel;

  @BeforeAll
  static void createRoleIdentities() throws Exception {
    for (String role : new String[]{"CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE"}) {
      String client = "acceptance-" + role.toLowerCase();
      String secret = UUID.randomUUID().toString();
      TOKENS.put(role, createIsolatedRoleClient(client, secret, role));
    }
    customerToken = TOKENS.get("CUSTOMER");
    dispatcherToken = TOKENS.get("DISPATCHER");
  }

  @Test
  void everyBackendIsHealthyFromCleanServiceDatabases() throws Exception {
    for (int port = 18080; port <= 18085; port++) {
      assertStatus("health", "GET", "http://127.0.0.1:" + port + "/actuator/health", null, 200);
    }
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> receiptCases() {
    return java.util.stream.Stream.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE", "ANONYMOUS")
        .flatMap(role -> java.util.stream.Stream.of(18080, 18082)
            .map(port -> org.junit.jupiter.params.provider.Arguments.of(role, port)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("receiptCases")
  void signedRolesAndAnonymousAreEnforcedAtGatewayAndParcelService(String role, int port) throws Exception {
    String receipt = "/api/v1/parcels/" + UUID.randomUUID() + "/receipt";
    int expected = role.equals("ANONYMOUS") ? 401 : role.equals("COURIER") ? 403 : 404;
    assertStatus(role, "GET", "http://127.0.0.1:" + port + receipt, TOKENS.get(role), expected);
  }

  @Test
  void forgedSignaturesAreDeniedIndependently() throws Exception {
    String[] parts = customerToken.split("\\.");
    // Change an early signature byte, not the padding bits of the last base64 character.
    parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
    String forged = String.join(".", parts);
    for (int port : new int[]{18080, 18081, 18082, 18083, 18084, 18085}) {
      String path = switch (port) {
        case 18081 -> "/api/v1/users/me";
        case 18083 -> "/api/v1/courier/deliveries";
        case 18084 -> "/api/v1/tracking/mine";
        case 18085 -> "/api/v1/merchant/webhooks";
        default -> "/api/v1/parcels/" + UUID.randomUUID() + "/receipt";
      };
      assertStatus("FORGED", "GET", "http://127.0.0.1:" + port + path, forged, 401);
    }
  }

  @Test
  void wrongAudienceAndForeignIssuerTokensAreDenied() throws Exception {
    String client = "acceptance-wrong-audience";
    String secret = UUID.randomUUID().toString();
    createServiceClient(client, secret, "CUSTOMER");
    var clients = send("GET", keycloak() + "/admin/realms/zentro/clients?clientId=" + client, adminToken(), null, 200);
    String id = clients.get(0).path("id").asText();
    var mappers = send("GET", keycloak() + "/admin/realms/zentro/clients/" + id + "/protocol-mappers/models", adminToken(), null, 200);
    for (var mapper : mappers) {
      if (mapper.path("name").asText().equals("E2E audience")) {
        send("DELETE", keycloak() + "/admin/realms/zentro/clients/" + id + "/protocol-mappers/models/" + mapper.path("id").asText(), adminToken(), null, 204);
      }
    }
    String wrongAudience = tokenForClient(client, secret);
    var claims = JSON.readTree(java.util.Base64.getUrlDecoder().decode(wrongAudience.split("\\.")[1]));
    assertThat(claims.path("aud").toString()).doesNotContain("zentro-api");
    for (int port : new int[]{18080, 18082}) {
      String uri = "http://127.0.0.1:" + port + "/api/v1/parcels/" + UUID.randomUUID() + "/receipt";
      assertStatus("WRONG_AUDIENCE", "GET", uri, wrongAudience, 401);
      assertStatus("FOREIGN_ISSUER", "GET", uri, adminToken(), 401);
    }
  }

  @Test
  void genuinelyExpiredKeycloakTokenIsDeniedAfterValidatorClockSkew() throws Exception {
    String client = "acceptance-short-lived";
    String secret = UUID.randomUUID().toString();
    createServiceClient(client, secret, "CUSTOMER");
    var clients = send("GET", keycloak() + "/admin/realms/zentro/clients?clientId=" + client, adminToken(), null, 200);
    String id = clients.get(0).path("id").asText();
    var config = (com.fasterxml.jackson.databind.node.ObjectNode) send("GET",
        keycloak() + "/admin/realms/zentro/clients/" + id, adminToken(), null, 200);
    config.putObject("attributes").put("access.token.lifespan", "1");
    send("PUT", keycloak() + "/admin/realms/zentro/clients/" + id, adminToken(), JSON.writeValueAsString(config), 204);
    String shortLived = tokenForClient(client, secret);
    var claims = JSON.readTree(java.util.Base64.getUrlDecoder().decode(shortLived.split("\\.")[1]));
    assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(1);
    String path = "/api/v1/parcels/" + UUID.randomUUID() + "/receipt";
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(90)).pollInterval(java.time.Duration.ofSeconds(2))
        .until(() -> sendRaw("GET", "http://127.0.0.1:18082" + path, shortLived, null).statusCode() == 401);
    assertStatus("EXPIRED", "GET", "http://127.0.0.1:18080" + path, shortLived, 401);
    assertStatus("EXPIRED", "GET", "http://127.0.0.1:18082" + path, shortLived, 401);
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> reportCases() {
    return java.util.stream.Stream.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE", "ANONYMOUS")
        .flatMap(role -> java.util.stream.Stream.of(18080, 18084)
            .map(port -> org.junit.jupiter.params.provider.Arguments.of(role, port)));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.MethodSource("reportCases")
  void reportingRolesAreEnforcedAtBothBoundaries(String role, int port) throws Exception {
    int expected = role.equals("ANONYMOUS") ? 401 : role.equals("DISPATCHER") ? 200 : 403;
    assertStatus(role, "GET", "http://127.0.0.1:" + port
        + "/api/v1/reports/deliveries/summary?from=2026-11-14&to=2026-11-14", TOKENS.get(role), expected);
  }

  @Test
  void disabledProfileRejectsStaleTokenUpdatesWithoutInventingGlobalRevocationPolicy() throws Exception {
    String token = createIsolatedRoleClient("acceptance-disabled-profile", UUID.randomUUID().toString(), "CUSTOMER");
    var profile = call("GET", "/api/v1/users/me", token, null);
    assertThat(profile.statusCode()).isEqualTo(200);
    String id = JSON.readTree(profile.body()).path("id").asText();
    assertThat(call("PATCH", "/api/v1/admin/users/" + id, dispatcherToken,
        "{\"accountStatus\":\"DISABLED\",\"reason\":\"Acceptance fixture\"}").statusCode()).isEqualTo(200);
    for (int port : new int[]{18080, 18081}) {
      int actual = sendRaw("PATCH", "http://127.0.0.1:" + port + "/api/v1/users/me", token,
          "{\"name\":\"Should be denied\"}").statusCode();
      assertThat(actual).isEqualTo(403);
      System.out.printf("ACCEPTANCE actor=DISABLED_STALE_TOKEN endpoint=port%d/users/me method=PATCH expected=403 actual=%d%n", port, actual);
    }
    int receipt = sendRaw("GET", "http://127.0.0.1:18082/api/v1/parcels/" + UUID.randomUUID() + "/receipt", token, null).statusCode();
    System.out.printf("OBSERVATION actor=DISABLED_STALE_TOKEN endpoint=S2/receipt approvedExpected=UNVERIFIED actual=%d policy=R06%n", receipt);
    // No global disablement expectation is approved. This observation is not security sign-off.
  }

  @Test
  @org.junit.jupiter.api.Order(1)
  void realBookingPersistsOnceAndReachesPublicTrackingWithoutBusinessSeeding() throws Exception {
    String body = orderBody();
    String key = UUID.randomUUID().toString();
    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:18080/api/v1/parcels"))
        .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + customerToken)
        .header("Content-Type", "application/json").header("Idempotency-Key", key)
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    var first = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(first.statusCode()).isEqualTo(201);
    var booking = JSON.readTree(first.body());
    bookedParcel = booking.path("id").asText();
    var replay = HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(replay.body()).path("id").asText()).isEqualTo(booking.path("id").asText());
    try (var connection = java.sql.DriverManager.getConnection(jdbcUrl("parcel_pricing_db"), "parcel_pricing_user", "parcel-pricing-only")) {
      try (var statement = connection.prepareStatement("SELECT count(*) FROM parcel.parcel WHERE id = ?")) {
        statement.setObject(1, UUID.fromString(booking.path("id").asText()));
        try (var result = statement.executeQuery()) { result.next(); assertThat(result.getInt(1)).isEqualTo(1); }
      }
      try (var statement = connection.prepareStatement("SELECT count(*) FROM parcel.parcel_status_history WHERE parcel_id = ?")) {
        statement.setObject(1, UUID.fromString(booking.path("id").asText()));
        try (var result = statement.executeQuery()) { result.next(); assertThat(result.getInt(1)).isZero(); }
      }
      try (var statement = connection.prepareStatement("SELECT count(*) FROM parcel.outbox_event WHERE aggregate_id = ? AND event_type = 'parcel.created'")) {
        statement.setObject(1, UUID.fromString(booking.path("id").asText()));
        try (var result = statement.executeQuery()) { result.next(); assertThat(result.getInt(1)).isEqualTo(1); }
      }
    }
    assertStatus("OWNER", "GET", "http://127.0.0.1:18082/api/v1/parcels/" + bookedParcel + "/receipt", customerToken, 200);
    assertStatus("FOREIGN", "GET", "http://127.0.0.1:18082/api/v1/parcels/" + bookedParcel + "/receipt", TOKENS.get("MERCHANT"), 404);
    String tracking = "/api/v1/tracking/" + booking.path("trackingRef").asText();
    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(30))
        .pollInterval(java.time.Duration.ofSeconds(2)).untilAsserted(() ->
        assertThat(call("GET", tracking, null, null).statusCode()).isEqualTo(200));
    assertSingleCreatedEvent(tracking);
  }

  @Test
  @org.junit.jupiter.api.Order(3)
  void trackingRestartPreservesConsumedCreatedFactEvenWhileReadHydrationIsBlocked() throws Exception {
    assertThat(bookedParcel).isNotBlank();
    assertThat(consumedFacts()).isEqualTo(1);
    restartTracking();
    assertStatus("health-after-restart", "GET", "http://127.0.0.1:18084/actuator/health", null, 200);
    assertThat(consumedFacts()).isEqualTo(1);
    System.out.println("ACCEPTANCE scenario=tracking-restart expectedCreatedFacts=1 actualCreatedFacts=1");
  }

  private static int consumedFacts() throws Exception {
    try (var db = java.sql.DriverManager.getConnection(jdbcUrl("tracking_reporting_db"), "tracking_user", "tracking-only");
         var query = db.prepareStatement("SELECT count(*) FROM tracking.tracking_event WHERE parcel_id = ? AND status = 'CREATED'")) {
      query.setObject(1, UUID.fromString(bookedParcel));
      try (var result = query.executeQuery()) { result.next(); return result.getInt(1); }
    }
  }

  @Test
  @org.junit.jupiter.api.Order(2)
  void ownerReceiptMustBeReachableThroughGateway() throws Exception {
    assertThat(bookedParcel).isNotBlank();
    // This must fail until Main 02 registers the approved receipt route; never accept 403 as success.
    assertStatus("OWNER", "GET", "http://127.0.0.1:18080/api/v1/parcels/" + bookedParcel + "/receipt", customerToken, 200);
  }

  private static void assertSingleCreatedEvent(String tracking) throws Exception {
    var response = call("GET", tracking + "/events", null, null);
    assertThat(response.statusCode()).isEqualTo(200);
    var events = JSON.readTree(response.body());
    assertThat(events.isArray()).isTrue();
    long created = java.util.stream.StreamSupport.stream(events.spliterator(), false)
        .filter(event -> event.path("status").asText().equals("CREATED")).count();
    assertThat(created).isEqualTo(1);
  }

  @Test
  @org.junit.jupiter.api.Order(Integer.MAX_VALUE)
  void serviceDatabaseDumpsRestoreIntoNewDisposableDatabases() throws Exception {
    // Last test: quiesce only fixture-owned writers so the independent recount is stable.
    stop(gateway); stop(s5); stop(s4); stop(s3); stop(s2); stop(s1);
    for (String[] source : new String[][]{
        {"identity_db", "identity.app_user"}, {"parcel_pricing_db", "parcel.parcel"},
        {"dispatch_courier_db", "dispatch.delivery_assignment"},
        {"tracking_reporting_db", "tracking.tracking_parcel"},
        {"notification_integration_db", "notification.notification_attempt"}}) {
      String database = source[0];
      String restored = "restore_" + database;
      String dump = "/tmp/acceptance-" + database + ".pgdump";
      assertThat(POSTGRES.execInContainer("pg_dump", "-U", "e2e_admin", "-d", database,
          "--format=custom", "--file=" + dump).getExitCode()).isZero();
      assertThat(POSTGRES.execInContainer("createdb", "-U", "e2e_admin", restored).getExitCode()).isZero();
      assertThat(POSTGRES.execInContainer("pg_restore", "-U", "e2e_admin", "-d", restored,
          "--exit-on-error", "--single-transaction", "--no-owner", "--no-acl", dump).getExitCode()).isZero();
      String sql = "SELECT count(*) FROM " + source[1];
      var before = POSTGRES.execInContainer("psql", "-U", "e2e_admin", "-d", database, "-Atc", sql);
      var after = POSTGRES.execInContainer("psql", "-U", "e2e_admin", "-d", restored, "-Atc", sql);
      assertThat(before.getExitCode()).isZero();
      assertThat(after.getExitCode()).isZero();
      assertThat(after.getStdout().trim()).isEqualTo(before.getStdout().trim());
      System.out.printf("RESTORE database=%s table=%s sourceRows=%s restoredRows=%s exit=0%n",
          database, source[1], before.getStdout().trim(), after.getStdout().trim());
    }
  }

  private static void assertStatus(String actor, String method, String uri, String token, int expected) throws Exception {
    int actual = sendRaw(method, uri, token, null).statusCode();
    // Safe export: no bearer values, identity data or request payloads.
    System.out.printf("ACCEPTANCE actor=%s method=%s endpoint=%s expected=%d actual=%d%n",
        actor, method, uri.replaceAll("[0-9a-f]{8}-[0-9a-f-]{27,}", "{id}"), expected, actual);
    assertThat(actual).as("%s %s %s", actor, method, uri).isEqualTo(expected);
  }
}
