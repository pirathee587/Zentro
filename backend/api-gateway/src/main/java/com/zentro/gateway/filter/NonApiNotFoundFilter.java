package com.zentro.gateway.filter;

import com.zentro.gateway.error.ProblemWriter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class NonApiNotFoundFilter implements WebFilter, Ordered {
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().value();
    boolean exposedHealth = path.equals("/actuator/health") || path.startsWith("/actuator/health/");
    if (!path.startsWith("/api/") && !exposedHealth && !path.equals("/actuator/prometheus")) {
      return ProblemWriter.write(exchange.getResponse(), HttpStatus.NOT_FOUND, "Not Found", "The requested resource was not found.", path);
    }
    return chain.filter(exchange);
  }

  @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
