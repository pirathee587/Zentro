package com.zentro.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdFilterTest {
  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void generatesCorrelationIdWhenMissingAndPropagatesToRequestAndResponse() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/parcels").build());
    AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
    GatewayFilterChain chain = ex -> {
      downstream.set(ex);
      return Mono.empty();
    };

    filter.filter(exchange, chain).block();

    String responseId = exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER);
    assertThat(responseId).isNotBlank();
    assertThat(downstream.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER))
        .isEqualTo(responseId);
  }

  @Test
  void preservesIncomingCorrelationIdAndAddsNoBufferingHeadersForTrackingStream() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/tracking/ZT-1234567890/stream")
            .header(CorrelationIdFilter.HEADER, "custom-corr-id-99")
            .build());
    AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
    GatewayFilterChain chain = ex -> {
      downstream.set(ex);
      return Mono.empty();
    };

    filter.filter(exchange, chain).block();

    assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER))
        .isEqualTo("custom-corr-id-99");
    assertThat(downstream.get().getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER))
        .isEqualTo("custom-corr-id-99");
    assertThat(exchange.getResponse().getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    assertThat(exchange.getResponse().getHeaders().getFirst("Cache-Control")).isEqualTo("no-cache");
  }
}

