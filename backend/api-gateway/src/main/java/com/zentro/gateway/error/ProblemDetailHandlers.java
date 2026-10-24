package com.zentro.gateway.error;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class ProblemDetailHandlers implements WebExceptionHandler {
  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    if (exchange.getResponse().isCommitted()) return Mono.error(ex);
    HttpStatus status = HttpStatus.BAD_GATEWAY;
    String title = "Bad Gateway";
    if (ex instanceof org.springframework.security.core.AuthenticationException) {
      status = HttpStatus.UNAUTHORIZED;
      title = "Unauthorized";
    } else if (ex instanceof org.springframework.security.access.AccessDeniedException) {
      status = HttpStatus.FORBIDDEN;
      title = "Forbidden";
    } else if (ex instanceof org.springframework.cloud.gateway.support.NotFoundException) {
      status = HttpStatus.NOT_FOUND;
      title = "Not Found";
    } else if (ex instanceof org.springframework.cloud.gateway.support.TimeoutException
        || ex instanceof java.util.concurrent.TimeoutException) {
      status = HttpStatus.SERVICE_UNAVAILABLE;
      title = "Service Unavailable";
    }
    return ProblemWriter.write(exchange.getResponse(), status, title, "The upstream service could not complete the request.", exchange.getRequest().getPath().value());
  }
}
