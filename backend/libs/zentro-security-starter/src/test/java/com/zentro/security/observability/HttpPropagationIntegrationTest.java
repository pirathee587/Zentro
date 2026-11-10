package com.zentro.security.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.tracing.Tracer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = HttpPropagationIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"management.tracing.propagation.type=w3c", "management.tracing.baggage.enabled=false",
        "management.tracing.sampling.probability=1"})
@AutoConfigureObservability
class HttpPropagationIntegrationTest {
  private static final String TRACE = "0123456789abcdef0123456789abcdef";
  private static final HttpServer upstream = upstream();
  @LocalServerPort int port;
  @Autowired ObjectMapper mapper;
  private final HttpClient client = HttpClient.newHttpClient();

  static HttpServer upstream() {
    try {
      var server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/", exchange -> {
        var h = exchange.getRequestHeaders();
        String body = h.getFirst("X-Correlation-Id") + "|" + h.getFirst("traceparent") + "|" + h.getFirst("baggage");
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
      });
      server.start(); return server;
    } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
  }
  static String destination() { return "http://localhost:" + upstream.getAddress().getPort(); }
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("zentro.correlation.internal-base-urls[0]", () -> destination() + "/internal");
  }
  @AfterAll static void stop() { upstream.stop(0); }

  HttpResponse<String> get(String path, String id) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
        .header("traceparent", "00-" + TRACE + "-0123456789abcdef-01")
        .header("baggage", "secret=never-forward");
    if (id != null) request.header("X-Correlation-Id", id);
    return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  @Test void serverExtractsW3cAndInternalClientContinuesTraceWithoutBaggage() throws Exception {
    var response = get("/relay", "request-123");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains("request-123");
    assertThat(response.body()).startsWith("request-123|00-" + TRACE + "-").endsWith("|null");
    assertThat(response.body()).doesNotContain("-0123456789abcdef-01"); // client is a new span
    assertThat(mapper.readTree(get("/context", "context-1").body()).get("traceId").asText()).isEqualTo(TRACE);
  }

  @Test void externalDestinationReceivesNoPropagationHeaders() throws Exception {
    assertThat(get("/external", "request-123").body()).isEqualTo("null|null|null");
  }

  @Test void securityFailuresRetainCorrelation() throws Exception {
    assertThat(get("/secure", "request-401").statusCode()).isEqualTo(401);
    assertThat(get("/secure", "request-401").headers().firstValue("X-Correlation-Id")).contains("request-401");
    var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/forbidden"))
        .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("user:password".getBytes()))
        .header("X-Correlation-Id", "request-403").GET().build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains("request-403");
  }

  @Test void callableHasCorrelationAndRedispatchKeepsResponseId() throws Exception {
    var response = get("/async", "async-123");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("async-123|" + TRACE);
    assertThat(response.headers().firstValue("X-Correlation-Id")).contains("async-123");
    assertThat(MDC.get("correlationId")).isNull();
  }

  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class App {
    @Bean JwtDecoder decoder() { return org.mockito.Mockito.mock(JwtDecoder.class); }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
      return http.csrf(c -> c.disable()).authorizeHttpRequests(a -> a
          .requestMatchers("/secure").authenticated().requestMatchers("/forbidden").denyAll().anyRequest().permitAll())
          .httpBasic(org.springframework.security.config.Customizer.withDefaults()).build();
    }
    @Bean org.springframework.security.core.userdetails.UserDetailsService users() {
      return new org.springframework.security.provisioning.InMemoryUserDetailsManager(
          org.springframework.security.core.userdetails.User.withUsername("user").password("{noop}password").roles("CUSTOMER").build());
    }
    @Bean Endpoints endpoints(RestClient.Builder builder, Tracer tracer) { return new Endpoints(builder.build(), tracer); }
  }

  @RestController
  static class Endpoints {
    private final RestClient client;
    private final Tracer tracer;
    Endpoints(RestClient client, Tracer tracer) { this.client = client; this.tracer = tracer; }
    @GetMapping("/context") Map<String, String> context() {
      return Map.of("correlationId", MDC.get("correlationId"), "traceId", tracer.currentSpan().context().traceId());
    }
    @GetMapping("/relay") String relay() { return client.get().uri(destination() + "/internal/echo").retrieve().body(String.class); }
    @GetMapping("/external") String external() { return client.get().uri(destination() + "/external").retrieve().body(String.class); }
    @GetMapping("/async") Callable<String> async() {
      return () -> MDC.get("correlationId") + "|" + (tracer.currentSpan() == null ? "null" : tracer.currentSpan().context().traceId());
    }
    @GetMapping({"/secure", "/forbidden"}) String secured() { return "secured"; }
  }
}
