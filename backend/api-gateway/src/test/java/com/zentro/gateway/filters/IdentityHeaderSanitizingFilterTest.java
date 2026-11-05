package com.zentro.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class IdentityHeaderSanitizingFilterTest {
  private final IdentityHeaderSanitizingFilter filter = new IdentityHeaderSanitizingFilter();

  @Test
  void stripsUntrustedIdentityHeadersWhilePreservingLegitimateHeaders() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/users/me")
            .header("Authorization", "Bearer valid-jwt")
            .header("X-Correlation-Id", "corr-1")
            .header("X-User-Id", "spoofed-user")
            .header("X-Roles", "ROLE_DISPATCHER")
            .header("X-Authenticated-User", "admin")
            .header("X-Remote-User", "root")
            .build());
    AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
    GatewayFilterChain chain = ex -> {
      downstream.set(ex);
      return Mono.empty();
    };

    filter.filter(exchange, chain).block();

    HttpHeaders headers = downstream.get().getRequest().getHeaders();
    assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer valid-jwt");
    assertThat(headers.getFirst("X-Correlation-Id")).isEqualTo("corr-1");
    assertThat(headers.getFirst("X-User-Id")).isNull();
    assertThat(headers.getFirst("X-Roles")).isNull();
    assertThat(headers.getFirst("X-Authenticated-User")).isNull();
    assertThat(headers.getFirst("X-Remote-User")).isNull();
  }
}

