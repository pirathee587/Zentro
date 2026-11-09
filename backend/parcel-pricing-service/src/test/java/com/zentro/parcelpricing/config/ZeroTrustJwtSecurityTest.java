package com.zentro.parcelpricing.config;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.zentro.parcelpricing.exception.GlobalPricingExceptionHandler;
import com.zentro.parcelpricing.pricing.controller.AdminPricingRuleController;
import com.zentro.parcelpricing.pricing.controller.ZoneController;
import com.zentro.parcelpricing.pricing.rules.PricingRule;
import com.zentro.parcelpricing.pricing.service.PricingService;
import com.zentro.parcelpricing.pricing.service.ZoneService;
import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies Zero Trust JWT authentication and authorization in S2 (parcel-pricing-service)
 * using the real {@link ZentroSecurityAutoConfiguration}, real S2 controllers and RS256-signed
 * tokens validated against a local JWKS server (no mocked {@code JwtDecoder}).
 */
@WebMvcTest(controllers = {AdminPricingRuleController.class, ZoneController.class})
@Import({ZentroSecurityAutoConfiguration.class, GlobalPricingExceptionHandler.class})
class ZeroTrustJwtSecurityTest {
  private static final String ISSUER = "http://localhost:8180/realms/zentro";
  private static final String AUDIENCE = "zentro-api";
  private static final String ADMIN_RULES = "/api/v1/admin/pricing-rules";

  private static RSAKey trustedRsaKey;
  private static RSAKey untrustedRsaKey;
  private static HttpServer jwksServer;
  private static String jwksUri;

  @Autowired private MockMvc mockMvc;
  @MockBean private PricingService pricingService;
  @MockBean private ZoneService zoneService;

  @BeforeAll
  static void startJwksServer() throws Exception {
    trustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s2-trusted-kid").generate();
    // Same kid, different key material: simulates a forged token.
    untrustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s2-trusted-kid").generate();

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
  }

  private static String signToken(
      RSAKey signingKey, String issuer, List<String> audience, Instant expiresAt, List<String> roles)
      throws Exception {
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

  private static String validToken(String... roles) throws Exception {
    return signToken(trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of(roles));
  }

  private static String hmacToken(String secret, String issuer, List<String> audience, List<String> roles) throws Exception {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .subject(UUID.randomUUID().toString())
        .issuer(issuer)
        .issueTime(Date.from(Instant.now().minusSeconds(30)))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .claim("realm_access", Map.of("roles", roles));
    if (audience != null) {
      claims.audience(audience);
    }
    SignedJWT signedJwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(),
        claims.build());
    signedJwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
    return signedJwt.serialize();
  }

  private static String unsignedToken(String issuer, List<String> audience, List<String> roles) {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .subject(UUID.randomUUID().toString())
        .issuer(issuer)
        .issueTime(Date.from(Instant.now().minusSeconds(30)))
        .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        .claim("realm_access", Map.of("roles", roles));
    if (audience != null) {
      claims.audience(audience);
    }
    return new PlainJWT(claims.build()).serialize();
  }

  @Test
  @DisplayName("Valid RS256 JWT with ROLE_DISPATCHER can read admin pricing rules")
  void acceptsValidSignedDispatcherToken() throws Exception {
    when(pricingService.getActiveRule()).thenReturn(PricingRule.defaultStandard());

    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("DISPATCHER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleName").value("STANDARD_DEFAULT"));
  }

  @Test
  @DisplayName("Valid RS256 JWT for any authenticated role can read delivery zones")
  void acceptsValidSignedCustomerTokenOnZones() throws Exception {
    when(zoneService.getActiveZones()).thenReturn(ZoneService.defaultZones());

    mockMvc.perform(get("/api/v1/zones").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("CUSTOMER")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("ZONE_COLOMBO_METRO"));
  }

  @Test
  @DisplayName("ADMIN is not a supported role for delivery zones")
  void rejectsAdminRoleOnZones() throws Exception {
    mockMvc.perform(get("/api/v1/zones").header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("ADMIN")))
        .andExpect(status().isForbidden());
    verifyNoInteractions(zoneService);
  }

  @Test
  @DisplayName("Invalid JWT signature signed by untrusted key is rejected with 401")
  void rejectsInvalidSignature() throws Exception {
    String token = signToken(
        untrustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("DISPATCHER"));

    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Expired JWT is rejected with 401")
  void rejectsExpiredToken() throws Exception {
    String token = signToken(
        trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120), List.of("DISPATCHER"));

    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("JWT with wrong issuer is rejected with 401")
  void rejectsWrongIssuer() throws Exception {
    String token = signToken(trustedRsaKey, "http://evil-issuer.local/realms/zentro",
        List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("DISPATCHER"));

    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("JWT with wrong audience or missing audience claim is rejected with 401")
  void rejectsWrongOrMissingAudience() throws Exception {
    String wrongAud = signToken(
        trustedRsaKey, ISSUER, List.of("wrong-audience"), Instant.now().plusSeconds(300), List.of("DISPATCHER"));
    String missingAud = signToken(
        trustedRsaKey, ISSUER, null, Instant.now().plusSeconds(300), List.of("DISPATCHER"));

    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAud))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + missingAud))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Direct service access without a token or with forged gateway headers is rejected with 401")
  void rejectsMissingAuthenticationAndForgedGatewayHeaders() throws Exception {
    mockMvc.perform(get(ADMIN_RULES))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));

    mockMvc.perform(get(ADMIN_RULES)
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", UUID.randomUUID().toString())
            .header("X-User-Roles", "ROLE_DISPATCHER"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Valid JWT with insufficient role (CUSTOMER / COURIER on admin pricing rules) is rejected with 403")
  void rejectsInsufficientRole() throws Exception {
    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("CUSTOMER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied"));
    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("COURIER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Malformed JWT string is rejected with 401")
  void rejectsMalformedJwt() throws Exception {
    for (String badToken : List.of("not-a-jwt", "abc.def", "header.payload.signature.extra")) {
      mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + badToken))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.detail").value("Authentication required"));
    }
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Tampered JWT payload with forged claims is rejected with 401")
  void rejectsTamperedJwt() throws Exception {
    String valid = validToken("DISPATCHER");
    String[] parts = valid.split("\\.");
    String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"attacker\",\"realm_access\":{\"roles\":[\"DISPATCHER\"]}}".getBytes(StandardCharsets.UTF_8));
    String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("JWT with unsupported symmetric algorithm HS256 is rejected with 401")
  void rejectsUnsupportedAlgorithmHs256() throws Exception {
    String token = hmacToken("secret01234567890123456789012345678901", ISSUER, List.of(AUDIENCE), List.of("DISPATCHER"));
    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Unsigned token with alg:none is rejected with 401")
  void rejectsUnsignedToken() throws Exception {
    String token = unsignedToken(ISSUER, List.of(AUDIENCE), List.of("DISPATCHER"));
    mockMvc.perform(get(ADMIN_RULES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Gateway-forwarded request carrying an invalid token is rejected independently by downstream service")
  void rejectsGatewayForwardedRequestWithInvalidToken() throws Exception {
    String expiredToken = signToken(
        trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120), List.of("DISPATCHER"));

    mockMvc.perform(get(ADMIN_RULES)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", "dispatcher-1")
            .header("X-User-Roles", "ROLE_DISPATCHER")
            .header("X-Correlation-Id", "gateway-trace-456"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(pricingService);
  }
}
