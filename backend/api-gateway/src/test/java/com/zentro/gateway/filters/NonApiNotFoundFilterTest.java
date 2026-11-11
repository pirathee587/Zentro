package com.zentro.gateway.filters;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class NonApiNotFoundFilterTest {
  private final NonApiNotFoundFilter filter = new NonApiNotFoundFilter();

  @Test
  void returns404ProblemJsonForNonApiAndUnexposedActuatorPaths() {
    AtomicBoolean chainCalled = new AtomicBoolean(false);
    WebFilterChain chain = ex -> {
      chainCalled.set(true);
      return Mono.empty();
    };

    MockServerWebExchange rootRequest = MockServerWebExchange.from(
        MockServerHttpRequest.get("/unknown-page").build());
    filter.filter(rootRequest, chain).block();

    assertThat(chainCalled.get()).isFalse();
    assertThat(rootRequest.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(rootRequest.getResponse().getBodyAsString().block())
        .contains("\"status\":404", "\"instance\":\"/unknown-page\"");
  }

  @Test
  void forwardsApiAndAllowedActuatorPathsToFilterChain() {
    for (String path : new String[] {"/api/v1/parcels", "/actuator/health", "/actuator/health/liveness", "/actuator/prometheus", "/graphql"}) {
      AtomicBoolean chainCalled = new AtomicBoolean(false);
      WebFilterChain chain = ex -> {
        chainCalled.set(true);
        return Mono.empty();
      };
      MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());

      filter.filter(exchange, chain).block();

      assertThat(chainCalled.get()).as("chain called for %s", path).isTrue();
    }
  }
}

