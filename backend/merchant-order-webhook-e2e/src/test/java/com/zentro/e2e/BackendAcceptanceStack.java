package com.zentro.e2e;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;

/** Main 10 shared support. Real services and approved contracts; no business-row seeding. */
abstract class BackendAcceptanceStack extends DisposableGatewayStack {
  private static Map<String, String> trackingConfiguration;
  @BeforeAll
  static void startRemainingBackendServices() throws Exception {
    createDatabase("dispatch_user", "dispatch-only", "dispatch_courier_db");
    createDatabase("tracking_user", "tracking-only", "tracking_reporting_db");
    s3 = launch("dispatch-courier-service", 18083, Map.ofEntries(
        Map.entry("DB_URL", jdbcUrl("dispatch_courier_db")), Map.entry("DB_USER", "dispatch_user"),
        Map.entry("DB_PASSWORD", "dispatch-only"), Map.entry("JWT_ISSUER_URI", issuer),
        Map.entry("JWT_JWKS_URI", jwksUri()), Map.entry("JWT_AUDIENCE", "zentro-api"),
        Map.entry("IDENTITY_GRPC_HOST", "127.0.0.1"), Map.entry("IDENTITY_GRPC_PORT", "9081"),
        Map.entry("PARCEL_GRPC_HOST", "127.0.0.1"), Map.entry("PARCEL_GRPC_PORT", "9082"),
        Map.entry("POD_TOKEN_URI", issuer + "/protocol/openid-connect/token"),
        Map.entry("POD_CLIENT_ID", "zentro-dispatch-service"), Map.entry("POD_CLIENT_SECRET", DISPATCH_SERVICE_SECRET),
        Map.entry("POD_WORKER_ENABLED", "true"), Map.entry("OUTBOX_ENABLED", "true"),
        Map.entry("SPRING_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers())));
    trackingConfiguration = Map.ofEntries(
        Map.entry("DB_URL", jdbcUrl("tracking_reporting_db")), Map.entry("DB_USER", "tracking_user"),
        Map.entry("DB_PASSWORD", "tracking-only"), Map.entry("JWT_ISSUER_URI", issuer),
        Map.entry("JWT_JWKS_URI", jwksUri()), Map.entry("JWT_AUDIENCE", "zentro-api"),
        Map.entry("KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers()), Map.entry("KAFKA_CONSUMER_ENABLED", "true"),
        Map.entry("PARCEL_GRPC_HOST", "127.0.0.1"), Map.entry("PARCEL_GRPC_PORT", "9082"),
        Map.entry("DISPATCH_GRPC_HOST", "127.0.0.1"), Map.entry("DISPATCH_GRPC_PORT", "9083"),
        Map.entry("SERVICE_TOKEN_URI", issuer + "/protocol/openid-connect/token"),
        Map.entry("SERVICE_CLIENT_ID", "zentro-tracking-service"), Map.entry("SERVICE_CLIENT_SECRET", TRACKING_SERVICE_SECRET));
    s4 = launch("tracking-reporting-service", 18084, trackingConfiguration);
    stop(gateway);
    gateway = launch("api-gateway", 18080, Map.of(
        "S1_URI", "http://127.0.0.1:18081", "S2_URI", "http://127.0.0.1:18082",
        "S3_URI", "http://127.0.0.1:18083", "S4_URI", "http://127.0.0.1:18084", "S5_URI", "http://127.0.0.1:18085",
        "JWT_ISSUER_URI", issuer, "JWT_JWKS_URI", jwksUri(), "JWT_AUDIENCE", "zentro-api"));
  }

  protected static void restartTracking() throws Exception {
    stop(s4);
    s4 = launch("tracking-reporting-service", 18084, trackingConfiguration);
  }

  protected static java.net.http.HttpResponse<String> book(String token, String body, String key) throws Exception {
    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:18080/api/v1/parcels"))
        .timeout(java.time.Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
        .header("Content-Type", "application/json").header("Idempotency-Key", key)
        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
    return HTTP.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
  }

  protected static String createIsolatedRoleClient(String client, String secret, String role) throws Exception {
    String user = createServiceClient(client, secret, role);
    String path = keycloak() + "/admin/realms/zentro/users/" + user + "/role-mappings/realm";
    var mappings = send("GET", path, adminToken(), null, 200);
    var remove = JSON.createArrayNode();
    for (var mapping : mappings) {
      if (!mapping.path("name").asText().equals(role)) remove.add(mapping);
    }
    if (!remove.isEmpty()) send("DELETE", path, adminToken(), JSON.writeValueAsString(remove), 204);
    String token = tokenForClient(client, secret);
    var applicationRoles = realmRoles(token).stream()
        .filter(java.util.Set.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE")::contains).toList();
    org.assertj.core.api.Assertions.assertThat(applicationRoles).containsExactly(role);
    return token;
  }
}
