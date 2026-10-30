package com.zentro.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayUpstreamFailureTests {
  private static final MockWebServer upstream = startUpstream();
  private static final int closedPort = findClosedPort();

  @Autowired WebTestClient client;
  @LocalServerPort int port;

  @DynamicPropertySource
  static void gatewayProperties(DynamicPropertyRegistry registry) {
    registry.add("S1_URI", () -> "http://127.0.0.1:" + closedPort);
    registry.add("S2_URI", () -> upstream.url("/").toString());
    registry.add("S3_URI", () -> upstream.url("/").toString());
    registry.add("S4_URI", () -> upstream.url("/").toString());
    registry.add("S5_URI", () -> upstream.url("/").toString());
    registry.add("JWT_ISSUER_URI", () -> "https://issuer.invalid/realms/zentro");
    registry.add("JWT_AUDIENCE", () -> "zentro-api");
    registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "150ms");
  }

  private static MockWebServer startUpstream() {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        if (request.getPath().startsWith("/api/v1/zones/slow")) {
          return new MockResponse().setHeadersDelay(750, TimeUnit.MILLISECONDS).setBody("slow upstream");
        }
        if (request.getPath().startsWith("/api/v1/zones/broken")) {
          return new MockResponse().setResponseCode(503).setBody("private-upstream-host.local:8082 internal diagnostic");
        }
        return new MockResponse().setResponseCode(200).setBody("ok");
      }
    });
    try { server.start(); } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    return server;
  }

  private static int findClosedPort() {
    try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    catch (IOException e) { throw new ExceptionInInitializerError(e); }
  }

  @AfterAll
  static void stopUpstream() throws IOException { upstream.shutdown(); }

  @Test
  void refusedUpstreamReturnsSafe503WithCorrelationId() {
    client.get().uri("/api/v1/users/me").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .exchange().expectStatus().isEqualTo(503).expectHeader().contentType("application/problem+json")
        .expectHeader().exists("X-Correlation-Id").expectBody().jsonPath("$.status").isEqualTo(503)
        .jsonPath("$.detail").value(value -> assertThat(value.toString()).doesNotContain("127.0.0.1", "808", "identity"));
  }

  @Test
  void slowUpstreamReturnsSafe504WithCorrelationId() {
    client.get().uri("/api/v1/zones/slow").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .exchange().expectStatus().isEqualTo(504).expectHeader().contentType("application/problem+json")
        .expectHeader().exists("X-Correlation-Id").expectBody().jsonPath("$.status").isEqualTo(504)
        .jsonPath("$.detail").value(value -> assertThat(value.toString()).doesNotContain("localhost", "808", "zone"));
  }

  @Test
  void upstreamServerErrorsAreReplacedWithSafe502Response() {
    client.get().uri("/api/v1/zones/broken").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .exchange().expectStatus().isEqualTo(502).expectHeader().contentType("application/problem+json")
        .expectHeader().exists("X-Correlation-Id").expectBody().jsonPath("$.status").isEqualTo(502)
        .jsonPath("$.detail").value(value -> assertThat(value.toString()).doesNotContain("private-upstream-host", "8082", "diagnostic"));
  }

  @TestConfiguration
  static class DecoderStub {
    @Bean @Primary ReactiveJwtDecoder testJwtDecoder() {
      return token -> {
        if (!token.startsWith("test.")) return Mono.error(new org.springframework.security.oauth2.jwt.BadJwtException("Invalid test token"));
        String role = token.substring("test.".length());
        List<String> roles = List.of(role.startsWith("ROLE_") ? role.substring(5) : role);
        return Mono.just(Jwt.withTokenValue(token).header("alg", "none").issuer("https://issuer.invalid/realms/zentro")
            .subject("test-user").audience(List.of("zentro-api")).claim("realm_access", Map.of("roles", roles)).build());
      };
    }
  }
}
