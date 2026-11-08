package com.zentro.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({GatewayBehaviorTests.DecoderStub.class, GatewayDefaultDenyTests.UnsecuredRoute.class})
class GatewayDefaultDenyTests {
  private static final MockWebServer UPSTREAM = start();
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  WebTestClient client;

  @BeforeEach void httpClient() {
    client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static MockWebServer start() {
    try {
      var server = new MockWebServer();
      server.start();
      return server;
    } catch (java.io.IOException error) { throw new ExceptionInInitializerError(error); }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("JWT_ISSUER_URI", () -> "https://issuer.invalid/realms/zentro");
    registry.add("JWT_AUDIENCE", () -> "zentro-api");
  }

  @TestConfiguration
  static class UnsecuredRoute {
    @Bean RouteLocator testOnlyRouteWithoutSecurityRule(RouteLocatorBuilder builder) {
      return builder.routes().route("audit-unsecured-test-only", route -> route
          .path("/api/v1/audit-unsecured/**").uri(UPSTREAM.url("/").toString())).build();
    }
  }

  @AfterAll static void shutdown() throws Exception { UPSTREAM.shutdown(); }

  @Test
  void unknownApiVariantsAndNewUnsecuredRouteFailClosedForEveryIdentity() throws Exception {
    // A response is queued so an accidental forward fails quickly rather than waiting for the proxy timeout.
    UPSTREAM.enqueue(new MockResponse().setBody("must not be reachable"));
    for (String path : List.of("/api/v1/unlisted", "/api/v1/unlisted/", "/api/v1/UNLISTED",
        "/api/v1/another-unknown/resource", "/api/v1/audit-unsecured/probe", "/api/v1/customer/parcels")) {
      for (String role : List.of("ANONYMOUS", "CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE", "no-role")) {
        var request = client.get().uri(path);
        if (!role.equals("ANONYMOUS")) request.header("Authorization", "Bearer test.ROLE_" + role);
        int expected = role.equals("ANONYMOUS") ? 401 : 403;
        request.exchange().expectStatus().isEqualTo(expected)
            .expectHeader().contentType("application/problem+json").expectHeader().exists("X-Correlation-Id")
            .expectBody().jsonPath("$.status").isEqualTo(expected).jsonPath("$.instance").exists()
            .jsonPath("$.detail").value(value -> assertThat(value.toString())
                .doesNotContain("localhost", "127.0.0.1", "808", "identity", "parcel", "stack"));
      }
    }
    assertThat(UPSTREAM.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void encodedSlashIsRejectedByExistingFirewallBeforeRouting() throws Exception {
    for (String role : List.of("ANONYMOUS", "CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE")) {
      var request = client.get().uri(URI.create("http://localhost:" + port + "/api/v1/unlisted%2Fchild"));
      if (!role.equals("ANONYMOUS")) request.header("Authorization", "Bearer test.ROLE_" + role);
      request.exchange().expectStatus().isBadRequest().expectHeader().exists("X-Correlation-Id")
          .expectBody().isEmpty();
    }
    assertThat(UPSTREAM.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
  }

  @Test
  void corsPreflightForListedAndUnlistedRoutesNeverReachesUpstream() throws Exception {
    for (String path : List.of("/api/v1/users/me", "/api/v1/audit-unsecured/probe")) {
      client.options().uri(path).header("Origin", "http://localhost:3000")
          .header("Access-Control-Request-Method", "GET")
          .header("Access-Control-Request-Headers", "authorization")
          .exchange().expectStatus().isOk().expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }
    assertThat(UPSTREAM.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
  }
}
