package com.zentro.notificationintegration.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local HTTP stand-in for a dependency in resilience tests. It can answer normally, answer late, send the body in
 * slow chunks, return any status or close the connection without answering, and it counts every request.
 */
public final class StubHttpServer implements AutoCloseable {

  @FunctionalInterface
  private interface Behaviour {
    void handle(HttpExchange exchange) throws IOException, InterruptedException;
  }

  private final HttpServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();
  private final AtomicInteger requests = new AtomicInteger();
  private volatile Behaviour behaviour = exchange -> send(exchange, 200, "{}");
  private volatile String lastAuthorization;

  private StubHttpServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    server.createContext("/", exchange -> {
      requests.incrementAndGet();
      lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
      exchange.getRequestBody().readAllBytes();
      try {
        behaviour.handle(exchange);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
      } catch (IOException ignored) {
        // The client gave up (timeout) while the stub was still answering.
      } finally {
        exchange.close();
      }
    });
    server.setExecutor(executor);
    server.start();
  }

  public static StubHttpServer start() {
    return new StubHttpServer();
  }

  /** A local port with nothing listening, for "connection refused". */
  public static int unusedPort() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void respond(int status, String json) {
    behaviour = exchange -> send(exchange, status, json);
  }

  public void respondAfter(Duration delay, int status, String json) {
    behaviour = exchange -> {
      Thread.sleep(delay.toMillis());
      send(exchange, status, json);
    };
  }

  /** Sends the headers at once, then the body in {@code chunks} parts with {@code pause} before each part. */
  public void trickle(int status, String json, int chunks, Duration pause) {
    behaviour = exchange -> {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, body.length);
      OutputStream out = exchange.getResponseBody();
      int size = (body.length + chunks - 1) / chunks;
      for (int offset = 0; offset < body.length; offset += size) {
        Thread.sleep(pause.toMillis());
        out.write(body, offset, Math.min(size, body.length - offset));
        out.flush();
      }
    };
  }

  /** Reads the request, then closes the connection without sending a status line. */
  public void closeWithoutReply() {
    behaviour = exchange -> { };
  }

  public int requests() {
    return requests.get();
  }

  public String lastAuthorization() {
    return lastAuthorization;
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }

  private static void send(HttpExchange exchange, int status, String json) throws IOException {
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      exchange.getResponseBody().write(body);
    }
  }
}
