package com.zentro.gateway.error;

import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import reactor.core.publisher.Mono;

public final class ProblemWriter {
  private ProblemWriter() {}

  public static Mono<Void> write(ServerHttpResponse response, HttpStatus status, String title, String detail, String instance) {
    response.setStatusCode(status);
    response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));
    String body = "{\"type\":\"about:blank\",\"title\":\"" + escape(title) + "\",\"status\":" + status.value()
        + ",\"detail\":\"" + escape(detail) + "\",\"instance\":\"" + escape(instance) + "\"}";
    DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
  }
}
