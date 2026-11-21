package com.zentro.e2e;

import static com.zentro.e2e.DisposableGatewayStack.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/** Opt-in deployment configuration acceptance. Never attaches to the developer's networks. */
final class CourierLocalIntegrationStack {
  private static Network network;
  private static final List<GenericContainer<?>> services = new ArrayList<>();
  private static final Map<String, GenericContainer<?>> byModule = new HashMap<>();
  private static final String PUBLIC_ISSUER = "http://localhost:8180/realms/zentro";
  private static final String INTERNAL_JWKS =
      "http://zentro-keycloak:8080/realms/zentro/protocol/openid-connect/certs";

  static boolean enabled() { return Boolean.getBoolean("zentro.e2e.containerNetwork"); }

  static void start() throws Exception {
    network = Network.newNetwork();
    POSTGRES.withNetwork(network).withNetworkAliases("postgres");
    KAFKA.withNetwork(network).withNetworkAliases("kafka");
    KEYCLOAK.withNetwork(network).withNetworkAliases("zentro-keycloak")
        .withEnv("KC_HOSTNAME", "http://localhost:8180");
    Startables.deepStart(java.util.stream.Stream.of(POSTGRES, KAFKA)).join();
    initializeDatabases();
    createDatabase("dispatch_user", "dispatch-only", "dispatch_courier_db");
    createDatabase("tracking_user", "tracking-only", "tracking_reporting_db");
    startKeycloak();
    issuer = PUBLIC_ISSUER;
    createTestClient();
    dispatcherToken = tokenForClient(DISPATCHER_CLIENT, DISPATCHER_SECRET);
    customerToken = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);

    launch("identity-partner-service", 8081, Map.of(
        "DB_URL", database("identity_db"), "DB_USER", "identity_user", "DB_PASSWORD", "identity-only",
        "GRPC_SERVER_ENABLED", "false"));
    launch("parcel-pricing-service", 8082, Map.of(
        "DB_URL", database("parcel_pricing_db"), "DB_USER", "parcel_pricing_user", "DB_PASSWORD", "parcel-pricing-only",
        "OUTBOX_PUBLISHER_ENABLED", "true", "ZENTRO_ASSIGNMENT_ELIGIBLE_STATUSES", "PICKED_UP",
        "ZENTRO_ASSIGNMENT_DISPATCH_BASE_URL", "http://dispatch-courier-service:8083"));
    launch("dispatch-courier-service", 8083, Map.of(
        "DB_URL", database("dispatch_courier_db"), "DB_USER", "dispatch_user", "DB_PASSWORD", "dispatch-only",
        "IDENTITY_BASE_URL", "http://identity-partner-service:8081", "PARCEL_BASE_URL", "http://parcel-pricing-service:8082",
        "POD_WORKER_ENABLED", "false", "OUTBOX_ENABLED", "true"));
    launch("tracking-reporting-service", 8084, Map.of(
        "DB_URL", database("tracking_reporting_db"), "DB_USER", "tracking_user", "DB_PASSWORD", "tracking-only",
        "KAFKA_CONSUMER_ENABLED", "true", "ZENTRO_DASHBOARD_DISPATCH_BASE_URL", "http://dispatch-courier-service:8083"));
    launch("api-gateway", 8080, Map.of(
        "S1_URI", "http://identity-partner-service:8081", "S2_URI", "http://parcel-pricing-service:8082",
        "S3_URI", "http://dispatch-courier-service:8083", "S4_URI", "http://tracking-reporting-service:8084"));
    CourierDashboardFlowIT.configureAuthenticationFixture();
    assertThat(byModule.get("api-gateway").execInContainer("wget", "-q", "-T", "5", "-O", "/dev/null", INTERNAL_JWKS)
        .getExitCode()).as("isolated Docker DNS and internal Keycloak certificates").isZero();
  }

  private static String database(String name) { return "jdbc:postgresql://postgres:5432/" + name; }

  private static void launch(String module, int port, Map<String, String> properties) {
    Path jar = MODULE.getParent().resolve(module + "/target/" + module + "-0.1.0-SNAPSHOT.jar");
    assertThat(jar).isRegularFile();
    var env = new HashMap<>(properties);
    if (module.equals("dispatch-courier-service")) {
      // S3 Flyway is off by default (shared Supabase safety). Enable it only for the disposable network database.
      assertThat(env.get("DB_URL")).startsWith("jdbc:postgresql://postgres:5432/");
      env.put("SPRING_FLYWAY_ENABLED", "true");
      env.put("SPRING_FLYWAY_VALIDATE_ON_MIGRATE", "true");
    }
    env.put("SERVER_PORT", Integer.toString(port));
    env.put("JWT_ISSUER_URI", PUBLIC_ISSUER);
    env.put("JWT_JWKS_URI", INTERNAL_JWKS);
    env.put("JWT_AUDIENCE", "zentro-api");
    env.put("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
    env.put("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092");
    env.put("JAVA_TOOL_OPTIONS", "-XX:ActiveProcessorCount=2");
    env.put("JAVA_OPTS", "-Xms96m -Xmx256m");
    var container = new GenericContainer<>("zentro-" + module + ":latest")
        .withNetwork(network).withNetworkAliases(module)
        .withExposedPorts(port).withEnv(env)
        .withCopyFileToContainer(MountableFile.forHostPath(jar), "/app/app.jar")
        .waitingFor(Wait.forHttp("/actuator/health/liveness").forPort(port).forStatusCode(200)
            .withStartupTimeout(Duration.ofMinutes(2)));
    services.add(container);
    byModule.put(module, container);
    container.start();
  }

  static String origin(String module, int defaultPort) {
    var container = byModule.get(module);
    return container == null ? "http://127.0.0.1:" + defaultPort
        : "http://" + container.getHost() + ":" + container.getMappedPort(container.getExposedPorts().get(0));
  }

  static void stop() {
    for (int index = services.size() - 1; index >= 0; index--) services.get(index).stop();
    services.clear();
    byModule.clear();
    KEYCLOAK.stop(); KAFKA.stop(); POSTGRES.stop();
    if (network != null) { network.close(); network = null; }
  }
}
