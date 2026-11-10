package com.zentro.gateway.filters;

import com.zentro.gateway.constants.GatewayConstants;
import com.zentro.gateway.exception.ProblemWriter;
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
          getHeaders().setContentType(MediaType.valueOf(GatewayConstants.PROBLEM_JSON_MEDIA_TYPE));
          return ProblemWriter.write(getDelegate(), HttpStatus.BAD_GATEWAY, "Bad Gateway",
              "The upstream service returned an error.", exchange.getRequest().getPath().value());
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

