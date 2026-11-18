package com.zentro.trackingreporting.graphql.dashboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.client.RestClient;

/** Isolated HTTP fixtures with synthetic credentials; never contacts running project services. */
class CourierDashboardHttpClientsTest {
  @Test void deploymentEnvironmentKeysResolveToThePropertiesUsedByTheClients() {
    var environment = new org.springframework.core.env.StandardEnvironment();
    environment.getPropertySources().addFirst(new org.springframework.core.env.SystemEnvironmentPropertySource(
        "synthetic-dashboard-config", java.util.Map.of(
          "ZENTRO_DASHBOARD_DISPATCH_BASE_URL", "http://dispatch-courier-service:8083")));
    assertThat(environment.getProperty("zentro.dashboard.dispatch-base-url")).isEqualTo("http://dispatch-courier-service:8083");
  }
  @Test void snapshotForwardsCourierBearerAndPreservesCanonicalId() throws Exception {
    UUID parcel = UUID.randomUUID();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext("/internal/courier-dashboard/assignments", exchange -> {
      assertThat(exchange.getRequestMethod()).isEqualTo("GET");
      assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-courier");
      assertThat(exchange.getRequestHeaders().getFirst("x-courier-authorization")).isNull();
      calls.incrementAndGet();
      byte[] body = ("[{\"parcelId\":\"" + parcel + "\",\"assignedAt\":\"2026-11-15T10:00:00Z\",\"assignmentState\":\"ASSIGNED\",\"completionState\":\"PENDING\"}]").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
    });
    server.start();
    try {
      var client = new CourierAssignmentSnapshotClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
      assertThat(client.snapshot("synthetic-courier").getFirst().parcelId()).isEqualTo(parcel);
      assertThat(calls.get()).isEqualTo(1);
    } finally { server.stop(0); }
  }
  @Test void snapshotFailuresAndMalformedResponsesAreNotTreatedAsEmptySuccess() throws Exception {
    for (int status : new int[] {403, 404, 503, 200}) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/internal/courier-dashboard/assignments", exchange -> {
        byte[] body = (status == 200 ? "[{\"assignmentState\":\"ASSIGNED\"}]" : "{\"detail\":\"private-detail\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body); exchange.close();
      }); server.start();
      try {
        var client = new CourierAssignmentSnapshotClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
        assertThatThrownBy(() -> client.snapshot("synthetic-courier"))
            .isInstanceOf(CourierDashboardException.class)
            .hasMessageNotContaining("private-detail");
      } finally { server.stop(0); }
    }
  }
  @Test void recognizesOnlyExplicitCourierProblemCodesAndDoesNotMisclassifyAnAbsentRoute() throws Exception {
    record Case(int status, String code, Class<? extends RuntimeException> expected, String dashboardCode) {}
    for (var example : java.util.List.of(
        new Case(404, "", CourierDashboardException.class, "ASSIGNMENT_SNAPSHOT_UNAVAILABLE"),
        new Case(404, "COURIER_PROFILE_NOT_FOUND", CourierDashboardException.class, "COURIER_PROFILE_NOT_FOUND"),
        new Case(403, "COURIER_ACCESS_DENIED", AccessDeniedException.class, null),
        new Case(401, "COURIER_UNAUTHENTICATED", org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class, null))) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/internal/courier-dashboard/assignments", exchange -> {
        byte[] body = ("{\"code\":\"" + example.code() + "\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/problem+json");
        exchange.sendResponseHeaders(example.status(), body.length); exchange.getResponseBody().write(body); exchange.close();
      }); server.start();
      try {
        var client = new CourierAssignmentSnapshotClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort());
        assertThatThrownBy(() -> client.snapshot("synthetic-courier")).isInstanceOf(example.expected()).satisfies(error -> {
          if (example.dashboardCode() != null) assertThat(((CourierDashboardException) error).code()).isEqualTo(example.dashboardCode());
        });
      } finally { server.stop(0); }
    }
  }
}
