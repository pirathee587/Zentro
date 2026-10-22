package com.zentro.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import com.zentro.gateway.error.ProblemWriter;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {
  private final int trackingLimit;
  private final int defaultLimit;
  private final boolean trustForwardedFor;
  private final Cache<String, Bucket> buckets = Caffeine.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).maximumSize(100_000).build();

  public RateLimitFilter(@Value("${zentro.rate-limit.tracking-per-minute:30}") int trackingLimit,
                         @Value("${zentro.rate-limit.default-per-minute:300}") int defaultLimit,
                         @Value("${zentro.rate-limit.trust-forwarded-for:false}") boolean trustForwardedFor) {
    this.trackingLimit = trackingLimit;
    this.defaultLimit = defaultLimit;
    this.trustForwardedFor = trustForwardedFor;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String ip = clientIp(exchange);
    boolean tracking = exchange.getRequest().getPath().value().startsWith("/api/v1/tracking/");
    int limit = tracking ? trackingLimit : defaultLimit;
    Bucket bucket = buckets.get(ip + (tracking ? ":tracking" : ":default"), key -> Bucket.builder()
        .addLimit(Bandwidth.classic(limit, Refill.intervally(limit, Duration.ofMinutes(1)))).build());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    if (probe.isConsumed()) return chain.filter(exchange);
    long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
    exchange.getResponse().getHeaders().set("Retry-After", Long.toString(seconds));
    return ProblemWriter.write(exchange.getResponse(), HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
        "Request rate limit exceeded.", exchange.getRequest().getPath().value());
  }

  private String clientIp(ServerWebExchange exchange) {
    if (trustForwardedFor) {
      String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
      if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
    }
    return exchange.getRequest().getRemoteAddress() == null ? "unknown" : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
  }

  @Override public int getOrder() { return -100; }
}
