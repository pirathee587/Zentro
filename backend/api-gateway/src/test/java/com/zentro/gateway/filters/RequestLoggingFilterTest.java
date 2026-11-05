package com.zentro.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RequestLoggingFilterTest {
  private final RequestLoggingFilter filter = new RequestLoggingFilter();

  @Test
  void executesChainAndUsesLowestPrecedenceOrder() {
    AtomicBoolean called = new AtomicBoolean(false);
    GatewayFilterChain chain = exchange -> {
      called.set(true);
      exchange.getResponse().setStatusCode(HttpStatus.OK);
      return Mono.empty();
    };
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/parcels").build());

    filter.filter(exchange, chain).block();

    assertThat(called.get()).isTrue();
    assertThat(filter.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }
}

