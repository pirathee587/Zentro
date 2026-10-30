package com.zentro.gateway.filter;

import java.util.Set;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class IdentityHeaderSanitizingFilter implements GlobalFilter, Ordered {
  private static final Set<String> UNTRUSTED_IDENTITY_HEADERS = Set.of(
      "X-User-Id", "X-User", "X-Roles", "X-Role", "X-User-Roles",
      "X-Auth-User", "X-Auth-Roles", "X-Authenticated-User", "X-Authenticated-Roles",
      "X-Principal", "X-Forwarded-User", "X-Forwarded-Roles", "X-Remote-User", "X-Remote-Groups");

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerWebExchange sanitized = exchange.mutate().request(request -> request.headers(headers ->
        UNTRUSTED_IDENTITY_HEADERS.forEach(headers::remove))).build();
    return chain.filter(sanitized);
  }

  @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 1; }
}
