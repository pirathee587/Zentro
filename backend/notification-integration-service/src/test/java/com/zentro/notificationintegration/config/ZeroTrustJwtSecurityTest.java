package com.zentro.notificationintegration.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Verifies Zero Trust JWT authentication and authorization in S5 (notification-integration-service)
 * using the real {@link ZentroSecurityAutoConfiguration} and RS256-signed tokens against a local JWKS server.
 */
@WebMvcTest(controllers = ZeroTrustJwtSecurityTest.ZeroTrustProbeController.class)
@Import({ZentroSecurityAutoConfiguration.class, ZeroTrustJwtSecurityTest.ZeroTrustProbeController.class})
class ZeroTrustJwtSecurityTest {
  private static final String ISSUER = "http://localhost:8180/realms/zentro";
  private static final String AUDIENCE = "zentro-api";

  private static final RSAKey trustedRsaKey;
  private static final RSAKey untrustedRsaKey;
  private static final HttpServer jwksServer;
  private static final String jwksUri;

  static {
    try {
      trustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s5-trusted-kid").generate();
      untrustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s5-trusted-kid").generate();

      byte[] jwksBytes = new JWKSet(trustedRsaKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      jwksServer.createContext("/realms/zentro/protocol/openid-connect/certs", exchange -> {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, jwksBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(jwksBytes);
        }
      });
      jwksServer.start();
      jwksUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort()
          + "/realms/zentro/protocol/openid-connect/certs";
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @RestController
  static class ZeroTrustProbeController {
    @GetMapping("/actuator/health")
    Map<String, String> health() {
      return Map.of("status", "UP");
    }

    @GetMapping("/api/v1/notifications/probe")
    @PreAuthorize("hasRole('SERVICE')")
    Map<String, String> protectedEndpoint(@AuthenticationPrincipal Jwt jwt) {
      return Map.of("subject", jwt.getSubject());
    }
  }

  @Autowired
  private MockMvc mockMvc;

  @Test
  @DisplayName("Anonymous requests cannot retrieve runtime OpenAPI or Swagger UI documents")
  void runtimeApiDocsAreNotExposedAnonymously() throws Exception {
    mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
  }

  @AfterAll
  static void stopJwksServer() {
    if (jwksServer != null) {
      jwksServer.stop(0);
    }
  }

  @DynamicPropertySource
  static void registerSecurityProperties(DynamicPropertyRegistry registry) {
    registry.add("zentro.security.issuer-uri", () -> ISSUER);
    registry.add("zentro.security.audience", () -> AUDIENCE);
    registry.add("zentro.security.jwks-uri", () -> jwksUri);
    registry.add("JWT_JWKS_URI", () -> jwksUri);
    registry.add("JWT_ISSUER_URI", () -> ISSUER);
    registry.add("JWT_AUDIENCE", () -> AUDIENCE);
  }

  private static String signToken(
      RSAKey signingKey,
      String issuer,
      List<String> audience,
      Instant expiresAt,
      List<String> roles) throws Exception {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .subject(UUID.randomUUID().toString())
        .issuer(issuer)
        .issueTime(Date.from(Instant.now().minusSeconds(30)))
        .expirationTime(Date.from(expiresAt))
        .claim("realm_access", Map.of("roles", roles));
    if (audience != null) {
      claims.audience(audience);
    }
    SignedJWT signedJwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256)
            .keyID(signingKey.getKeyID())
            .type(JOSEObjectType.JWT)
            .build(),
        claims.build());
    signedJwt.sign(new RSASSASigner(signingKey));
    return signedJwt.serialize();
  }

  @Test
  @DisplayName("Valid RS256 JWT with required role is accepted")
  void acceptsValidSignedToken() throws Exception {
    String token = signToken(
        trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("SERVICE"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.subject").isNotEmpty());
  }

  @Test
  @DisplayName("Invalid JWT signature signed by untrusted key is rejected with 401")
  void rejectsInvalidSignature() throws Exception {
    String token = signToken(
        untrustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("SERVICE"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  @Test
  @DisplayName("Expired JWT is rejected with 401")
  void rejectsExpiredToken() throws Exception {
    String token = signToken(
        trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120), List.of("SERVICE"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  @Test
  @DisplayName("JWT with wrong issuer is rejected with 401")
  void rejectsWrongIssuer() throws Exception {
    String token = signToken(
        trustedRsaKey,
        "http://evil-issuer.local/realms/zentro",
        List.of(AUDIENCE),
        Instant.now().plusSeconds(300),
        List.of("SERVICE"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  @Test
  @DisplayName("JWT with wrong audience or missing audience claim is rejected with 401")
  void rejectsWrongOrMissingAudience() throws Exception {
    String wrongAudToken = signToken(
        trustedRsaKey, ISSUER, List.of("wrong-audience"), Instant.now().plusSeconds(300), List.of("SERVICE"));
    String missingAudToken = signToken(
        trustedRsaKey, ISSUER, null, Instant.now().plusSeconds(300), List.of("SERVICE"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + missingAudToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  @Test
  @DisplayName("Direct service access without authentication or with forged gateway headers is rejected with 401")
  void rejectsMissingAuthenticationAndForgedGatewayHeaders() throws Exception {
    mockMvc.perform(get("/api/v1/notifications/probe"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", UUID.randomUUID().toString())
            .header("X-User-Roles", "ROLE_SERVICE"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  @Test
  @DisplayName("Valid JWT with insufficient role is rejected with 403")
  void rejectsInsufficientRole() throws Exception {
    String customerToken = signToken(
        trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("CUSTOMER"));

    mockMvc.perform(get("/api/v1/notifications/probe")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied"));
  }

  @Test
  @DisplayName("Actuator health endpoint remains accessible without authentication")
  void permitsActuatorHealthWithoutAuth() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk());
  }
}

