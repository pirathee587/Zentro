package com.zentro.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayBehaviorTests {
  private static final Map<String, MockWebServer> upstreams = startUpstreams();
  private static final String REQUEST_BODY = "{\"source\":\"gateway-test\",\"value\":7}";

  @Autowired WebTestClient client;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    upstreams.forEach((name, server) -> registry.add(name + "_URI", () -> server.url("/").toString()));
    registry.add("JWT_ISSUER_URI", () -> "https://issuer.invalid/realms/zentro");
    registry.add("JWT_AUDIENCE", () -> "zentro-api");
    registry.add("zentro.rate-limit.trust-forwarded-for", () -> "true");
  }

  private static Map<String, MockWebServer> startUpstreams() {
    Map<String, MockWebServer> servers = Map.of(
        "S1", startUpstream("S1"),
        "S2", startUpstream("S2"),
        "S3", startUpstream("S3"),
        "S4", startUpstream("S4"),
        "S5", startUpstream("S5"));
    return servers;
  }

  private static MockWebServer startUpstream(String service) {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse().setResponseCode(200).setBody(service);
      }
    });
    try { server.start(); } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    return server;
  }

  @AfterAll
  static void stopUpstreams() throws IOException {
    for (MockWebServer server : upstreams.values()) server.shutdown();
  }

  @Test
  void everyConfiguredPrefixForwardsUnmodifiedToItsConfiguredService() throws Exception {
    clearRecordedRequests();
    List<RouteTarget> routes = List.of(
        new RouteTarget("/api/v1/users/me", "S1", "ROLE_CUSTOMER", false),
        new RouteTarget("/api/v1/merchants/me", "S1", "ROLE_CUSTOMER", false),
        new RouteTarget("/api/v1/admin/merchants/17", "S1", "ROLE_DISPATCHER", false),
        new RouteTarget("/api/v1/admin/users/17", "S1", "ROLE_DISPATCHER", false),
        new RouteTarget("/api/v1/admin/couriers/17", "S1", "ROLE_DISPATCHER", false),
        new RouteTarget("/api/v1/zones", "S2", "ROLE_CUSTOMER", false),
        new RouteTarget("/api/v1/parcels/estimate", "S2", "ROLE_CUSTOMER", false),
        new RouteTarget("/api/v1/merchant/orders/17", "S2", "ROLE_MERCHANT", false),
        new RouteTarget("/api/v1/admin/zones", "S2", "ROLE_DISPATCHER", false),
        new RouteTarget("/api/v1/admin/pricing-rules", "S2", "ROLE_DISPATCHER", false),
        new RouteTarget("/api/v1/dispatch/jobs", "S3", "ROLE_DISPATCHER", false),
        new RouteTarget("/api/v1/courier/jobs", "S3", "ROLE_COURIER", false),
        new RouteTarget("/api/v1/tracking/ZTR-1234567890/events", "S4", null, true),
        new RouteTarget("/api/v1/reports/summary", "S4", "ROLE_DISPATCHER", false),
        new RouteTarget("/api/v1/merchant/webhooks", "S5", "ROLE_MERCHANT", false),
        new RouteTarget("/api/v1/admin/audit-logs", "S5", "ROLE_DISPATCHER", false));

    int index = 0;
    for (RouteTarget route : routes) {
      String query = "?probe=route-" + index + "&mode=preserve";
      HttpMethod method = route.publicRoute() ? HttpMethod.GET : HttpMethod.PATCH;
      WebTestClient.RequestBodySpec request = client.method(method)
          .uri(URI.create("http://localhost:" + port + route.path() + query))
          .header("X-Forwarded-For", "198.51.100." + (index + 1))
          .header("X-User-Id", "spoofed-user")
          .header("X-Roles", "ROLE_ADMIN")
          .header("X-Authenticated-User", "spoofed-authenticated-user");
      if (route.token() != null) request.header("Authorization", "Bearer test." + route.token());
      if (!route.publicRoute()) request.bodyValue(REQUEST_BODY);
      request.exchange().expectStatus().isOk().expectBody(String.class).isEqualTo(route.service());

      RecordedRequest received = upstreams.get(route.service()).takeRequest(2, TimeUnit.SECONDS);
      assertThat(received).as("request routed to %s for %s", route.service(), route.path()).isNotNull();
      assertThat(received.getMethod()).isEqualTo(method.name());
      assertThat(received.getPath()).isEqualTo(route.path() + query);
      assertThat(received.getBody().readUtf8()).isEqualTo(route.publicRoute() ? "" : REQUEST_BODY);
      assertThat(received.getHeader("X-User-Id")).isNull();
      assertThat(received.getHeader("X-Roles")).isNull();
      assertThat(received.getHeader("X-Authenticated-User")).isNull();
      index++;
    }
  }

  @Test
  void unauthenticatedInvalidAndExpiredTokensAreRejectedOnProtectedRoutes() {
    client.get().uri("/api/v1/parcels").exchange().expectStatus().isUnauthorized();
    client.get().uri("/api/v1/parcels").header("Authorization", "Bearer invalid.token")
        .exchange().expectStatus().isUnauthorized();
    client.get().uri("/api/v1/parcels").header("Authorization", "Bearer test.expired")
        .exchange().expectStatus().isUnauthorized();
  }

  @Test
  void routeGroupsRejectWrongRolesAndRolelessTokens() {
    client.get().uri("/api/v1/admin/merchants").header("Authorization", "Bearer test.ROLE_COURIER")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/dispatch/jobs").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/merchant/orders").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/courier/jobs").header("Authorization", "Bearer test.ROLE_DISPATCHER")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/users/me").header("Authorization", "Bearer test.no-role")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/users/me").header("Authorization", "Bearer test.ROLE_SERVICE")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/api/v1/admin/users").header("Authorization", "Bearer test.ROLE_ADMIN")
        .exchange().expectStatus().isForbidden();
    client.post().uri("/api/v1/tracking/ZTR-1234567890/events").exchange().expectStatus().isUnauthorized();
  }

  @Test
  void explicitlyPublicTrackingAndHealthRoutesWorkWithoutToken() {
    client.get().uri("/api/v1/tracking/ZTR-1234567890").exchange().expectStatus().isOk();
    client.get().uri("/api/v1/tracking/ZTR-1234567890/events").exchange().expectStatus().isOk();
    client.get().uri("/api/v1/tracking/ZTR-1234567890/unlisted").exchange().expectStatus().isUnauthorized();
    client.get().uri("/actuator/health").exchange().expectStatus().isOk();
  }

  @Test
  void unknownRoutesReturnSafeJson404ForAnonymousAndAuthenticatedCallers() {
    for (WebTestClient.RequestHeadersSpec<?> request : List.of(
        client.get().uri("/api/v1/not-a-route"),
        client.get().uri("/api/v1/not-a-route").header("Authorization", "Bearer test.ROLE_CUSTOMER"))) {
      request.exchange().expectStatus().isNotFound().expectHeader().contentType("application/problem+json")
          .expectBody().jsonPath("$.status").isEqualTo(404).jsonPath("$.title").isEqualTo("Not Found")
          .jsonPath("$.detail").value(value -> assertThat(value.toString()).doesNotContain("localhost", "808", "S1", "S2", "S3", "S4", "S5"));
    }
    client.get().uri("/missing").exchange().expectStatus().isNotFound()
        .expectHeader().contentType("application/problem+json").expectBody().jsonPath("$.status").isEqualTo(404);
  }

  @Test
  void onlyKnownActuatorPathsArePublic() {
    client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
    client.get().uri("/actuator/prometheus").header("Authorization", "Bearer test.ROLE_SERVICE")
        .exchange().expectStatus().isForbidden();
    client.get().uri("/actuator/prometheus").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .exchange().expectStatus().isOk();
    client.get().uri("/actuator/info").exchange().expectStatus().isNotFound();
  }

  @Test
  void corsPreflightIsAcceptedWithoutTokenOnlyForAllowedOrigins() {
    client.options().uri("http://localhost:" + port + "/api/v1/parcels").header("Origin", "http://localhost:3000")
        .header("Access-Control-Request-Method", "GET").exchange().expectStatus().isOk()
        .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    client.get().uri("http://localhost:" + port + "/api/v1/parcels").header("Origin", "https://unknown.example")
        .exchange().expectStatus().isForbidden();
  }

  @Test
  void correlationIdsAreGeneratedAndPreservedOnForwardedRequests() throws Exception {
    clearRecordedRequests();
    String generated = client.get().uri("/api/v1/tracking/ZTR-1234567890")
        .header("X-Forwarded-For", "203.0.113.11").exchange().expectStatus().isOk()
        .returnResult(String.class).getResponseHeaders().getFirst("X-Correlation-Id");
    assertThat(generated).isNotBlank();
    RecordedRequest first = upstreams.get("S4").takeRequest();
    assertThat(first.getHeader("X-Correlation-Id")).isEqualTo(generated);

    client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.12")
        .header("X-Correlation-Id", "request-123").exchange().expectStatus().isOk()
        .expectHeader().valueEquals("X-Correlation-Id", "request-123");
    assertThat(upstreams.get("S4").takeRequest().getHeader("X-Correlation-Id")).isEqualTo("request-123");
  }

  @Test
  void trackingRateLimitReturnsRetryAfter() {
    for (int i = 0; i < 30; i++) {
      client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.30")
          .exchange().expectStatus().isOk();
    }
    client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.30")
        .exchange().expectStatus().isEqualTo(429).expectHeader().exists("Retry-After")
        .expectHeader().contentType("application/problem+json");
    client.get().uri("/api/v1/tracking/ZTR-1234567890").header("X-Forwarded-For", "203.0.113.31")
        .exchange().expectStatus().isOk();
  }

  private static void clearRecordedRequests() throws InterruptedException {
    for (MockWebServer server : upstreams.values()) {
      while (server.takeRequest(10, TimeUnit.MILLISECONDS) != null) { /* drain earlier test traffic */ }
    }
  }

  private record RouteTarget(String path, String service, String token, boolean publicRoute) {}

  @TestConfiguration
  static class DecoderStub {
    @Bean @Primary ReactiveJwtDecoder testJwtDecoder() {
      return token -> {
        if (token.equals("test.expired") || !token.startsWith("test.")) {
          return Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid test token"));
        }
        String role = token.substring("test.".length());
        List<String> roles = role.equals("no-role") ? List.of() : List.of(role.startsWith("ROLE_") ? role.substring(5) : role);
        return Mono.just(Jwt.withTokenValue(token).header("alg", "none").issuer("https://issuer.invalid/realms/zentro")
            .subject("test-user").audience(List.of("zentro-api")).claim("realm_access", Map.of("roles", roles)).build());
      };
    }
  }
}
