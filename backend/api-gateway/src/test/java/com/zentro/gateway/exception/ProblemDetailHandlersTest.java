package com.zentro.gateway.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.gateway.constants.GatewayConstants;
import io.netty.handler.timeout.ReadTimeoutException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class ProblemDetailHandlersTest {
  private final ProblemDetailHandlers handler = new ProblemDetailHandlers();

  @Test
  void mapsTimeoutExceptionsTo504GatewayTimeout() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/zones/slow").build());

    handler.handle(exchange, new RuntimeException(ReadTimeoutException.INSTANCE)).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    assertThat(exchange.getResponse().getHeaders().getContentType())
        .isEqualTo(MediaType.valueOf(GatewayConstants.PROBLEM_JSON_MEDIA_TYPE));
    String body = exchange.getResponse().getBodyAsString().block();
    assertThat(body).contains("\"status\":504", "\"title\":\"Gateway Timeout\"", "\"instance\":\"/api/v1/zones/slow\"");
  }

  @Test
  void mapsConnectExceptionsTo503ServiceUnavailable() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/users/me").build());

    handler.handle(exchange, new RuntimeException(new ConnectException("Connection refused"))).block();

    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    String body = exchange.getResponse().getBodyAsString().block();
    assertThat(body).contains("\"status\":503", "\"title\":\"Service Unavailable\"");
  }

  @Test
  void mapsAuthenticationAndAccessDeniedAndNotFoundExceptions() {
    MockServerWebExchange unauth = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/parcels").build());
    handler.handle(unauth, new BadCredentialsException("bad token")).block();
    assertThat(unauth.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

    MockServerWebExchange forbidden = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/admin/users").build());
    handler.handle(forbidden, new AccessDeniedException("denied")).block();
    assertThat(forbidden.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

    MockServerWebExchange notFound = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/unknown").build());
    handler.handle(notFound, new NotFoundException("route not found")).block();
    assertThat(notFound.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void problemWriterEscapesQuotesBackslashesAndNewlinesInJsonBody() {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/test").build());

    ProblemWriter.write(exchange.getResponse(), HttpStatus.BAD_GATEWAY,
        "Bad \"Gateway\"", "Line1\nLine2\\end", "/api/v1/test").block();

    String body = exchange.getResponse().getBodyAsString().block();
    assertThat(body).contains("\"title\":\"Bad \\\"Gateway\\\"\"");
    assertThat(body).contains("\"detail\":\"Line1 Line2\\\\end\"");
  }
}

