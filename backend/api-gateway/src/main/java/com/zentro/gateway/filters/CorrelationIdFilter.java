package com.zentro.gateway.filters;

import com.zentro.gateway.constants.GatewayConstants;
import java.util.UUID;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.server.WebFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CorrelationIdFilter implements WebFilter, Ordered {
  public static final String HEADER = GatewayConstants.CORRELATION_ID_HEADER;

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    var values = exchange.getRequest().getHeaders().get(HEADER);
    String incoming = values != null && values.size() == 1 ? values.getFirst() : null;
    String id = incoming != null && incoming.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        ? incoming : UUID.randomUUID().toString();
    exchange.getResponse().getHeaders().set(HEADER, id);
    exchange.getResponse().beforeCommit(() -> {
      exchange.getResponse().getHeaders().set(HEADER, id);
      return Mono.empty();
    });
    String[] segments = path.split("/");
    if (segments.length == 6 && segments[1].equals("api") && segments[2].equals("v1")
        && segments[3].equals("tracking") && segments[5].equals("stream")) {
      exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
      exchange.getResponse().getHeaders().set("Cache-Control", "no-cache");
    }
    ServerWebExchange updated = exchange.mutate()
        .request(request -> request.headers(headers -> {
          headers.set(HEADER, id);
          headers.remove("baggage");
        }))
        .build();
    // Reactor context is request-local; never hold a thread-local MDC scope across an asynchronous chain.
    return chain.filter(updated).contextWrite(context -> context.put("correlationId", id));
  }

  @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}

