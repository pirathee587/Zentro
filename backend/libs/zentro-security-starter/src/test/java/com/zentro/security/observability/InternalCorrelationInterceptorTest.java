package com.zentro.security.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

class InternalCorrelationInterceptorTest {
  @ParameterizedTest
  @ValueSource(strings = {"http://identity:8081/api/v1/users/me", "http://identity:8081/api/v1/admin/couriers",
      "http://parcel:8082/api/v1/parcels/123/status"})
  void allowsConfiguredInternalOriginsWithoutChangingAuthentication(String target) throws Exception {
    var interceptor = new InternalCorrelationInterceptor(List.of("http://identity:8081", "http://parcel:8082"));
    var request = request(target);
    MDC.put("correlationId", "request-123");
    try {
      interceptor.intercept(request, new byte[0], (req, body) -> {
        assertThat(req.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("request-123");
        assertThat(req.getHeaders().getFirst("traceparent")).isEqualTo("trace-context");
        assertThat(req.getHeaders().getFirst("baggage")).isNull();
        assertThat(req.getHeaders().getFirst("Authorization")).isEqualTo("Bearer existing-authentication");
        return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
      });
    } finally { MDC.remove("correlationId"); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://identity.evil:8081/", "http://identity:9999/", "https://identity:8081/",
      "http://keycloak:8180/token", "http://merchant:8081/", "http://user@identity:8081/"})
  void stripsPropagationForOtherDestinations(String target) throws Exception {
    var request = request(target);
    new InternalCorrelationInterceptor(List.of("http://identity:8081")).intercept(request, new byte[0], (req, body) -> {
      assertThat(req.getHeaders()).doesNotContainKeys("X-Correlation-Id", "traceparent", "tracestate", "baggage");
      return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK);
    });
  }

  private MockClientHttpRequest request(String target) {
    var request = new MockClientHttpRequest(HttpMethod.GET, URI.create(target));
    request.getHeaders().set("Authorization", "Bearer existing-authentication");
    request.getHeaders().set("X-Correlation-Id", "untrusted");
    request.getHeaders().set("traceparent", "trace-context");
    request.getHeaders().set("tracestate", "vendor=value");
    request.getHeaders().set("baggage", "secret=value");
    return request;
  }
}
