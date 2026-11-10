package com.zentro.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.gateway.constants.GatewayConstants;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

class CorsConfigTest {
  private final CorsConfig corsConfig = new CorsConfig();

  @Test
  void corsConfigurationSourceParsesOriginsAndConfiguresMethodsAndHeaders() {
    CorsConfigurationSource source = corsConfig.corsConfigurationSource(
        " http://localhost:3000 , http://localhost:5173 , ");
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.get("/api/v1/parcels").build());

    CorsConfiguration config = source.getCorsConfiguration(exchange);

    assertThat(config).isNotNull();
    assertThat(config.getAllowedOrigins()).containsExactly("http://localhost:3000", "http://localhost:5173");
    assertThat(config.getAllowedMethods()).containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    assertThat(config.getAllowedHeaders())
        .containsExactly("Authorization", "Content-Type", GatewayConstants.CORRELATION_ID_HEADER, "Idempotency-Key");
    assertThat(config.getExposedHeaders()).containsExactly(GatewayConstants.CORRELATION_ID_HEADER, "Location");
    assertThat(config.getAllowCredentials()).isFalse();
    assertThat(config.getMaxAge()).isNull();
  }

  @Test
  void existingBookingHeaderPassesPreflightButUnlistedOriginsAndHeadersAreDenied() {
    CorsConfiguration config = corsConfig.corsConfigurationSource("https://portal.example.invalid")
        .getCorsConfiguration(MockServerWebExchange.from(MockServerHttpRequest.options("/api/v1/parcels").build()));
    assertThat(config.checkOrigin("https://portal.example.invalid")).isEqualTo("https://portal.example.invalid");
    assertThat(config.checkOrigin("https://other.example.invalid")).isNull();
    assertThat(config.checkHeaders(List.of("Idempotency-Key", "Authorization"))).isNotNull();
    assertThat(config.checkHeaders(List.of("X-Unapproved-Header"))).isNull();
    assertThat(config.getAllowCredentials()).isFalse();
  }
}

