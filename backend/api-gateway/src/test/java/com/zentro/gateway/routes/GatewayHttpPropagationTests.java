package com.zentro.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.tracing.sampling.probability=1")
@AutoConfigureWebTestClient
@AutoConfigureObservability
@Import(GatewayBehaviorTests.DecoderStub.class)
class GatewayHttpPropagationTests {
  static final MockWebServer upstream = start();
  @Autowired WebTestClient client;
  static MockWebServer start() {
    var server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override public MockResponse dispatch(RecordedRequest request) {
        return new MockResponse().setBody(request.getHeader("X-Correlation-Id") + "|"
            + request.getHeader("traceparent") + "|" + request.getHeader("baggage"));
      }
    });
    try { server.start(); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    return server;
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("S4_URI", () -> upstream.url("/").toString());
    registry.add("JWT_ISSUER_URI", () -> "https://issuer.invalid/realms/zentro");
    registry.add("JWT_AUDIENCE", () -> "zentro-api");
  }
  @AfterAll static void stop() throws IOException { upstream.shutdown(); }

  @Test void forwardsCorrelationAndW3cTraceWithoutBaggage() {
    String trace = "0123456789abcdef0123456789abcdef";
    client.get().uri("/api/v1/tracking/ZT-1234567890")
        .header("X-Correlation-Id", "request-123")
        .header("traceparent", "00-" + trace + "-0123456789abcdef-01")
        .header("baggage", "secret=never-forward").exchange().expectStatus().isOk()
        .expectHeader().valueEquals("X-Correlation-Id", "request-123")
        .expectBody(String.class).value(body -> assertThat(body).startsWith("request-123|00-" + trace + "-").endsWith("|null"));
  }

  @Test void early401And403KeepSafeCorrelation() {
    client.get().uri("/api/v1/users/me").header("X-Correlation-Id", "request-401").exchange()
        .expectStatus().isUnauthorized().expectHeader().valueEquals("X-Correlation-Id", "request-401");
    client.get().uri("/api/v1/admin/users").header("Authorization", "Bearer test.ROLE_CUSTOMER")
        .header("X-Correlation-Id", "request-403").exchange()
        .expectStatus().isForbidden().expectHeader().valueEquals("X-Correlation-Id", "request-403");
  }
}
