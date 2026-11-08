package com.zentro.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class UpstreamResponseSanitizingFilterTest {
  private final UpstreamResponseSanitizingFilter filter = new UpstreamResponseSanitizingFilter();

  @Test
  void replacesUpstream5xxResponseWithSafe502ProblemJson() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/zones").build());
    GatewayFilterChain chain = mutated -> {
      mutated.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
      DataBuffer raw = mutated.getResponse().bufferFactory()
          .wrap("internal stack trace on host-db-01".getBytes(StandardCharsets.UTF_8));
      return mutated.getResponse().writeWith(Mono.just(raw));
    };

    filter.filter(exchange, chain).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    String body = exchange.getResponse().getBodyAsString().block();
    assertThat(body).contains("\"status\":502", "\"title\":\"Bad Gateway\"", "\"instance\":\"/api/v1/zones\"");
    assertThat(body).doesNotContain("host-db-01", "stack trace");
  }

  @Test
  void emptyUpstream5xxStillProducesSafeProblem() {
    MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/zones").build());
    filter.filter(exchange, mutated -> {
      mutated.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
      return mutated.getResponse().setComplete();
    }).block();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"instance\":\"/api/v1/zones\"");
  }

  @Test
  void passesThroughNon5xxUpstreamResponsesUnchanged() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/zones").build());
    GatewayFilterChain chain = mutated -> {
      mutated.getResponse().setStatusCode(HttpStatus.OK);
      DataBuffer raw = mutated.getResponse().bufferFactory()
          .wrap("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
      return mutated.getResponse().writeWith(Mono.just(raw));
    };

    filter.filter(exchange, chain).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(exchange.getResponse().getBodyAsString().block()).isEqualTo("{\"ok\":true}");
  }
}

