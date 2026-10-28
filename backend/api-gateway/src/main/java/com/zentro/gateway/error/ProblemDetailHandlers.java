package com.zentro.gateway.error;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class ProblemDetailHandlers implements WebExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ProblemDetailHandlers.class);

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
    if (exchange.getResponse().isCommitted()) return Mono.error(ex);
    HttpStatus status = HttpStatus.BAD_GATEWAY;
    String title = "Bad Gateway";
    Throwable rootCause = rootCause(ex);
    if (hasCause(ex, org.springframework.cloud.gateway.support.TimeoutException.class)
        || hasCause(ex, java.util.concurrent.TimeoutException.class)
        || hasCause(ex, io.netty.channel.ConnectTimeoutException.class)
        || hasCause(ex, io.netty.handler.timeout.ReadTimeoutException.class)) {
      status = HttpStatus.GATEWAY_TIMEOUT;
      title = "Gateway Timeout";
    } else if (ex instanceof org.springframework.security.core.AuthenticationException) {
      status = HttpStatus.UNAUTHORIZED;
      title = "Unauthorized";
    } else if (ex instanceof org.springframework.security.access.AccessDeniedException) {
      status = HttpStatus.FORBIDDEN;
      title = "Forbidden";
    } else if (ex instanceof org.springframework.cloud.gateway.support.NotFoundException
        || ex instanceof org.springframework.web.reactive.resource.NoResourceFoundException) {
      status = HttpStatus.NOT_FOUND;
      title = "Not Found";
    } else if (hasCause(ex, java.net.ConnectException.class)
        || hasCause(ex, java.net.UnknownHostException.class)
        || hasCause(ex, java.net.NoRouteToHostException.class)) {
      status = HttpStatus.SERVICE_UNAVAILABLE;
      title = "Service Unavailable";
    }
    if (status.is5xxServerError()) {
      log.warn("Gateway upstream failure: method={} correlationId={} exceptionType={}",
          exchange.getRequest().getMethod(), exchange.getResponse().getHeaders().getFirst("X-Correlation-Id"),
          rootCause.getClass().getSimpleName());
    }
    return ProblemWriter.write(exchange.getResponse(), status, title, "The upstream service could not complete the request.", exchange.getRequest().getPath().value());
  }

  private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (type.isInstance(current)) return true;
    }
    return false;
  }

  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) current = current.getCause();
    return current;
  }
}
