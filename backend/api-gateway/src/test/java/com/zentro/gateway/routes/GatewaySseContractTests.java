package com.zentro.gateway.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Real gateway HTTP with a finite SSE upstream; JWT validation is a declared test seam. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(GatewayBehaviorTests.DecoderStub.class)
class GatewaySseContractTests {
  static final MockWebServer upstream = start();
  @Autowired WebTestClient client;
  static MockWebServer start() {
    var server = new MockWebServer();
    try { server.start(); } catch (IOException ex) { throw new ExceptionInInitializerError(ex); }
    return server;
  }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("S4_URI", () -> upstream.url("/").toString());
    registry.add("JWT_ISSUER_URI", () -> "https://issuer.invalid/realms/zentro");
    registry.add("JWT_AUDIENCE", () -> "zentro-api");
  }
  @AfterAll static void stop() throws IOException { upstream.shutdown(); }

  @Test void authenticatedStreamReconnectPreservesEventCursorAndAuthorization() throws Exception {
    for (String cursor : new String[] {"", "event-1"}) {
      String body = "id: event-2\nevent: tracking\ndata: {\"status\":\"IN_TRANSIT\"}\n\n";
      upstream.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body));
      var request = client.get().uri("/api/v1/tracking/ZT-1234567890/stream")
          .header("Authorization", "Bearer test.ROLE_CUSTOMER")
          .header("X-Correlation-Id", "sse-reconnect").header("X-User-Id", "spoofed");
      if (!cursor.isEmpty()) request.header("Last-Event-ID", cursor);
      request.exchange().expectStatus().isOk()
          .expectHeader().contentTypeCompatibleWith("text/event-stream")
          .expectHeader().valueEquals("X-Correlation-Id", "sse-reconnect")
          .expectBody(String.class).isEqualTo(body);
      var forwarded = upstream.takeRequest(2, TimeUnit.SECONDS);
      assertThat(forwarded).isNotNull();
      assertThat(forwarded.getPath()).isEqualTo("/api/v1/tracking/ZT-1234567890/stream");
      assertThat(forwarded.getHeader("Authorization")).isEqualTo("Bearer test.ROLE_CUSTOMER");
      assertThat(forwarded.getHeader("Last-Event-ID")).isEqualTo(cursor.isEmpty() ? null : cursor);
      assertThat(forwarded.getHeader("X-User-Id")).isNull();
    }
  }

  @Test void expiredAndServiceOnlyTokensNeverReachStreamUpstream() throws Exception {
    client.get().uri("/api/v1/tracking/ZT-1234567890/stream")
        .header("Authorization", "Bearer test.expired").exchange().expectStatus().isUnauthorized();
    client.get().uri("/api/v1/tracking/ZT-1234567890/stream")
        .header("Authorization", "Bearer test.ROLE_SERVICE").exchange().expectStatus().isForbidden();
    assertThat(upstream.takeRequest(100, TimeUnit.MILLISECONDS)).isNull();
  }
}
