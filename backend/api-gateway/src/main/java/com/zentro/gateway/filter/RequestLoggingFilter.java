package com.zentro.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {
  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    long started = System.nanoTime();
    return chain.filter(exchange).doFinally(signal -> log.info("method={} path={} status={} durationMs={} correlationId={}",
        exchange.getRequest().getMethod(), exchange.getRequest().getPath().value(),
        exchange.getResponse().getStatusCode() == null ? 200 : exchange.getResponse().getStatusCode().value(),
        (System.nanoTime() - started) / 1_000_000,
        exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.HEADER)));
  }

  @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
