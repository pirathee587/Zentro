package com.zentro.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.WebFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

class CorrelationIdFilterTest {
  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "has space", "a\rb", "a\nb", "a\tb", "name@example.test", "a,b", "é", "<script>", "_prefix"})
  void replacesUnsafeIds(String id) {
    assertReplaced(id);
  }

  @Test void replacesOversizedAndDuplicateIds() {
    assertReplaced("a".repeat(129));
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
        .header(CorrelationIdFilter.HEADER, "one", "two"));
    filter.filter(exchange, ex -> Mono.empty()).block();
    assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isNotIn("one", "two");
  }

  private void assertReplaced(String id) {
    var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
        .header(CorrelationIdFilter.HEADER, id));
    filter.filter(exchange, ex -> {
      String accepted = ex.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER);
      assertThat(java.util.UUID.fromString(accepted).toString()).isEqualTo(accepted);
      return Mono.empty();
    }).block();
    assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isNotEqualTo(id);
  }

  @Test void concurrentReactiveRequestsKeepSeparateContextAndNeverTouchMdc() {
    org.slf4j.MDC.put("correlationId", "outer");
    try {
      reactor.core.publisher.Flux.range(0, 40).flatMap(i -> {
        String id = "request-" + i;
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/")
            .header(CorrelationIdFilter.HEADER, id).header("baggage", "secret=value"));
        return filter.filter(exchange, ex -> Mono.delay(java.time.Duration.ofMillis(5))
            .then(Mono.deferContextual(context -> {
              assertThat(context.<String>get("correlationId")).isEqualTo(id);
              assertThat(ex.getRequest().getHeaders().getFirst(CorrelationIdFilter.HEADER)).isEqualTo(id);
              assertThat(ex.getRequest().getHeaders().getFirst("baggage")).isNull();
              return Mono.empty();
            })));
      }).blockLast();
      assertThat(org.slf4j.MDC.get("correlationId")).isEqualTo("outer");
    } finally { org.slf4j.MDC.remove("correlationId"); }
  }

  @Test
  void generatesCorrelationIdWhenMissingAndPropagatesToRequestAndResponse() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/parcels").build());
    AtomicReference<ServerWebExchange> downstream = new AtomicReference<>();
    WebFilterChain chain = ex -> {
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
    WebFilterChain chain = ex -> {
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

