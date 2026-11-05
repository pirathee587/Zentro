package com.zentro.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RateLimitFilterTest {
  @Test
  void enforcesTrackingRateLimitAndSetsRetryAfterHeader() {
    RateLimitFilter filter = new RateLimitFilter(2, 10, true);
    GatewayFilterChain chain = ex -> Mono.empty();

    for (int i = 0; i < 2; i++) {
      MockServerWebExchange allowed = MockServerWebExchange.from(
          MockServerHttpRequest.get("/api/v1/tracking/ZT-1234567890")
              .header("X-Forwarded-For", "198.51.100.50")
              .build());
      filter.filter(allowed, chain).block();
      assertThat(allowed.getResponse().getStatusCode()).isNull();
    }

    MockServerWebExchange throttled = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/tracking/ZT-1234567890")
            .header("X-Forwarded-For", "198.51.100.50")
            .build());
    filter.filter(throttled, chain).block();

    assertThat(throttled.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(throttled.getResponse().getHeaders().getFirst("Retry-After")).isNotBlank();
    assertThat(throttled.getResponse().getBodyAsString().block())
        .contains("\"status\":429", "\"title\":\"Too Many Requests\"");
  }
}

