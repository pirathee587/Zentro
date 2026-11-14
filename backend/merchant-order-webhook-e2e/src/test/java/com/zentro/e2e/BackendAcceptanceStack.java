package com.zentro.e2e;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;

/** Main 10 shared support. Real services and approved contracts; no business-row seeding. */
abstract class BackendAcceptanceStack extends DisposableGatewayStack {
  @BeforeAll
  static void startRemainingBackendServices() throws Exception {
    createDatabase("dispatch_user", "dispatch-only", "dispatch_courier_db");
    createDatabase("tracking_user", "tracking-only", "tracking_reporting_db");
    String completionClient = "acceptance-completion";
    String completionSecret = UUID.randomUUID().toString();
    createServiceClient(completionClient, completionSecret, "SERVICE");
    s3 = launch("dispatch-courier-service", 18083, Map.ofEntries(
        Map.entry("DB_URL", jdbcUrl("dispatch_courier_db")), Map.entry("DB_USER", "dispatch_user"),
        Map.entry("DB_PASSWORD", "dispatch-only"), Map.entry("JWT_ISSUER_URI", issuer),
        Map.entry("JWT_JWKS_URI", issuer + "/protocol/openid-connect/certs"), Map.entry("JWT_AUDIENCE", "zentro-api"),
        Map.entry("IDENTITY_BASE_URL", "http://127.0.0.1:18081"), Map.entry("PARCEL_BASE_URL", "http://127.0.0.1:18082"),
        Map.entry("POD_TOKEN_URI", issuer + "/protocol/openid-connect/token"),
        Map.entry("POD_CLIENT_ID", completionClient), Map.entry("POD_CLIENT_SECRET", completionSecret),
        Map.entry("POD_WORKER_ENABLED", "true"), Map.entry("OUTBOX_ENABLED", "true"),
        Map.entry("SPRING_KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers())));
    s4 = launch("tracking-reporting-service", 18084, Map.of(
        "DB_URL", jdbcUrl("tracking_reporting_db"), "DB_USER", "tracking_user", "DB_PASSWORD", "tracking-only",
        "JWT_ISSUER_URI", issuer, "JWT_JWKS_URI", issuer + "/protocol/openid-connect/certs", "JWT_AUDIENCE", "zentro-api",
        "KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers(), "KAFKA_CONSUMER_ENABLED", "true"));
    stop(gateway);
    gateway = launch("api-gateway", 18080, Map.of(
        "S1_URI", "http://127.0.0.1:18081", "S2_URI", "http://127.0.0.1:18082",
        "S3_URI", "http://127.0.0.1:18083", "S4_URI", "http://127.0.0.1:18084", "S5_URI", "http://127.0.0.1:18085",
        "JWT_ISSUER_URI", issuer, "JWT_JWKS_URI", issuer + "/protocol/openid-connect/certs", "JWT_AUDIENCE", "zentro-api"));
  }
}
