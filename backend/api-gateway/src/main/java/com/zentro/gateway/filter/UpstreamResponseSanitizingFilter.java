package com.zentro.gateway.filter;

import java.nio.charset.StandardCharsets;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class UpstreamResponseSanitizingFilter implements GlobalFilter, Ordered {
  private static final byte[] SAFE_ERROR = ("{\"type\":\"about:blank\",\"title\":\"Bad Gateway\","
      + "\"status\":502,\"detail\":\"The upstream service returned an error.\"}")
      .getBytes(StandardCharsets.UTF_8);

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {
      @Override
      public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        if (getStatusCode() == null || !getStatusCode().is5xxServerError()) return super.writeWith(body);
        return Flux.from(body).doOnNext(DataBufferUtils::release).then(Mono.defer(() -> {
          setStatusCode(HttpStatus.BAD_GATEWAY);
          getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
          getHeaders().remove(HttpHeaders.CONTENT_ENCODING);
          getHeaders().setContentType(MediaType.valueOf("application/problem+json"));
          getHeaders().setContentLength(SAFE_ERROR.length);
          return super.writeWith(Mono.just(bufferFactory().wrap(SAFE_ERROR.clone())));
        }));
      }

      @Override
      public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        if (getStatusCode() == null || !getStatusCode().is5xxServerError()) return super.writeAndFlushWith(body);
        return writeWith(Flux.from(body).concatMap(publisher -> Flux.from(publisher)));
      }
    };
    return chain.filter(exchange.mutate().response(response).build());
  }

  @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 2; }
}
