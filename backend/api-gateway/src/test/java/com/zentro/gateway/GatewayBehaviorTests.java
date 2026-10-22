package com.zentro.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayBehaviorTests {
  private static final MockWebServer upstream = startUpstream();
  @Autowired WebTestClient client;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    String uri = upstream.url("/").toString();
    registry.add("S1_URI", () -> uri);
    registry.add("S2_URI", () -> uri);
    registry.add("S3_URI", () -> uri);
    registry.add("S4_URI", () -> uri);
    registry.add("S5_URI", () -> uri);
    registry.add("JWT_ISSUER_URI", () -> "https://issuer.invalid/realms/zentro");
    registry.add("JWT_AUDIENCE", () -> "zentro-api");
    registry.add("zentro.rate-limit.trust-forwarded-for", () -> "true");
  }

  private static MockWebServer startUpstream() {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse().setResponseCode(200).setBody("upstream stub");
      }
    });
    try { server.start(); } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    return server;
  }

  @AfterAll
  static void stopUpstream() throws IOException { upstream.shutdown(); }

  @Test
  void shouldReturn401ProblemForUnauthenticatedProtectedRoute() {
    client.get().uri("/api/v1/parcels").exchange().expectStatus().isUnauthorized()
        .expectHeader().contentType("application/problem+json").expectBody().jsonPath("$.status").isEqualTo(401);
  }

  @Test
  void shouldReturn200ForPublicTrackingLookup() {
    client.get().uri("/api/v1/tracking/ZTR-1234567890").exchange().expectStatus().isOk();
  }

  @Test
  void shouldProtectTrackingMineBeforePublicTracking() {
    client.get().uri("/api/v1/tracking/mine").exchange().expectStatus().isUnauthorized();
    client.get().uri("/api/v1/tracking/mine").header("Authorization", "Bearer test.ROLE_COURIER")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/tracking/mine").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .exchange().expectStatus().isOk();
    client.get().uri("/api/v1/tracking/mine").header("Authorization", "Bearer test.ROLE_MERCHANT")
        .exchange().expectStatus().isOk();
  }

  @Test
  void shouldAllowNoRoleAuthenticatedUserOnAnyAuthenticatedRoutes() {
    client.get().uri("/api/v1/users/me").header("Authorization", "Bearer test.no-role").exchange().expectStatus().isOk();
    client.get().uri("/api/v1/courier/jobs").header("Authorization", "Bearer test.no-role").exchange().expectStatus().isForbidden();
  }

  @Test
  void shouldDenyServiceOnlyAndUnknownAdminRoles() {
    client.get().uri("/api/v1/users/me").header("Authorization", "Bearer test.ROLE_SERVICE")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/admin/users").header("Authorization", "Bearer test.ADMIN")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/tracking/ZTR-1234567890").header("Authorization", "Bearer test.ROLE_SERVICE")
        .exchange().expectStatus().isForbidden();
  }

  @Test
  void shouldEnforceTheFourRoleMatrixAcrossProtectedRouteRules() {
    List<RouteRule> rules = List.of(
        new RouteRule("/api/v1/tracking/mine", Set.of("ROLE_CUSTOMER", "ROLE_MERCHANT")),
        new RouteRule("/api/v1/admin/users", Set.of("ROLE_DISPATCHER")),
        new RouteRule("/api/v1/dispatch/jobs", Set.of("ROLE_DISPATCHER")),
        new RouteRule("/api/v1/reports/summary", Set.of("ROLE_DISPATCHER")),
        new RouteRule("/api/v1/courier/jobs", Set.of("ROLE_COURIER")),
        new RouteRule("/api/v1/merchant/orders", Set.of("ROLE_MERCHANT")),
        new RouteRule("/api/v1/parcels", Set.of("ROLE_CUSTOMER", "ROLE_DISPATCHER")),
        new RouteRule("/api/v1/users/me", Set.of("ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER", "ROLE_ADMIN", "NO_ROLE")),
        new RouteRule("/api/v1/merchants/me", Set.of("ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER", "ROLE_ADMIN", "NO_ROLE")),
        new RouteRule("/api/v1/zones", Set.of("ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER", "ROLE_ADMIN", "NO_ROLE")));
    List<String> roles = List.of("ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER", "ROLE_SERVICE", "ROLE_ADMIN", "NO_ROLE");
    for (RouteRule rule : rules) {
      for (String role : roles) {
        boolean allowed = rule.allowedRoles().contains(role);
        int expected = allowed ? 200 : 403;
        var request = client.get().uri(rule.path());
        if (!role.equals("NO_ROLE")) request.header("Authorization", "Bearer test." + role);
        else request.header("Authorization", "Bearer test.no-role");
        request.exchange().expectStatus().value(status -> assertThat(status).as("role %s on %s", role, rule.path()).isEqualTo(expected));
      }
    }
  }

  @Test
  void shouldReturn403ForAuthenticatedUnknownApiAndProblem404ForNonApi() {
    client.get().uri("/api/v1/x").exchange().expectStatus().isUnauthorized();
    client.get().uri("/api/v1/x").header("Authorization", "Bearer test.no-role").exchange().expectStatus().isForbidden();
    client.get().uri("/missing").exchange().expectStatus().isNotFound()
        .expectHeader().contentType("application/problem+json").expectBody().jsonPath("$.status").isEqualTo(404);
  }

  @Test
  void shouldReturn401ForAnInvalidBearerToken() {
    client.get().uri("/api/v1/parcels").header("Authorization", "Bearer invalid.token")
        .exchange().expectStatus().isUnauthorized().expectHeader().contentType("application/problem+json");
  }

  @Test
  void shouldExposeHealthPubliclyAndPrometheusToAuthenticatedCallers() {
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    client.get().uri("/actuator/prometheus").header("Authorization", "Bearer test.ROLE_SERVICE")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/actuator/prometheus").header("Authorization", "Bearer test.no-role")
        .exchange().expectStatus().isOk();
  }

  @Test
  void shouldApplyCorsForAllowedOriginsAndAcceptPreflightWithoutToken() {
    client.options().uri("http://localhost:" + port + "/api/v1/parcels").header("Origin", "http://localhost:3000")
        .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isOk()
        .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    client.get().uri("http://localhost:" + port + "/api/v1/parcels").header("Origin", "https://unknown.example")
        .exchange().expectStatus().isForbidden();
  }

  @Test
  void shouldGenerateAndPreserveCorrelationIds() {
    String generated = client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.11").exchange().expectStatus().isOk()
        .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
    assertThat(generated).isNotBlank();
    client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.12").header("X-Correlation-Id", "request-123")
        .exchange().expectStatus().isOk().expectHeader().valueEquals("X-Correlation-Id", "request-123");
  }

  @Test
  void shouldReturn429WithRetryAfterAtTrackingLimit() {
    for (int i = 0; i < 30; i++) client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.30").exchange().expectStatus().isOk();
    client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.30").exchange().expectStatus().isEqualTo(429)
        .expectHeader().exists("Retry-After").expectHeader().contentType("application/problem+json");
    client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.31").exchange().expectStatus().isOk();
  }

  @TestConfiguration
  static class DecoderStub {
    @Bean @Primary ReactiveJwtDecoder testJwtDecoder() {
      return token -> {
        if (!token.startsWith("test.")) return Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid test token"));
        String role = token.substring("test.".length());
        String claimRole = role.startsWith("ROLE_") ? role.substring("ROLE_".length()) : role;
        List<String> roles = role.equals("no-role") ? List.of() : List.of(claimRole);
        return Mono.just(Jwt.withTokenValue(token).header("alg", "none").issuer("https://issuer.invalid/realms/zentro")
            .subject("test-user").audience(List.of("zentro-api")).claim("realm_access", Map.of("roles", roles)).build());
      };
    }
  }

  private record RouteRule(String path, Set<String> allowedRoles) {}
}
