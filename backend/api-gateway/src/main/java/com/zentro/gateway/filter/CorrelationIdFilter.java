package com.zentro.gateway.filter;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {
  public static final String HEADER = "X-Correlation-Id";

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
    String id = incoming == null || incoming.isBlank() ? UUID.randomUUID().toString() : incoming;
    exchange.getResponse().getHeaders().set(HEADER, id);
    String[] segments = path.split("/");
    if (segments.length == 6 && segments[1].equals("api") && segments[2].equals("v1")
        && segments[3].equals("tracking") && segments[5].equals("stream")) {
      exchange.getResponse().getHeaders().set("X-Accel-Buffering", "no");
      exchange.getResponse().getHeaders().set("Cache-Control", "no-cache");
    }
    ServerWebExchange updated = exchange.mutate().request(request -> request.headers(headers -> headers.set(HEADER, id))).build();
    return chain.filter(updated);
  }

  @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
}
