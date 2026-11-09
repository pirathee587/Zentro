package com.zentro.identitypartner.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.zentro.identitypartner.merchant.controller.MerchantApplicationController;
import com.zentro.identitypartner.merchant.service.MerchantApplicationService;
import com.zentro.identitypartner.user.controller.AdminUserController;
import com.zentro.identitypartner.user.controller.UserProfileController;
import com.zentro.identitypartner.user.service.UserProfileService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Zero Trust JWT validation verification in S1 (identity-partner-service).
 * Verifies that the service independently validates JWT signature, trusted issuer,
 * expected audience, expiration, algorithm restrictions, and role authorities against
 * a real JWKS server without trusting gateway headers or mocking the JwtDecoder.
 */
@WebMvcTest(controllers = {
    UserProfileController.class,
    AdminUserController.class,
    MerchantApplicationController.class,
})
@Import(ZentroSecurityAutoConfiguration.class)
class ZeroTrustSecurityTest {

  private static final String ISSUER = "http://localhost:8180/realms/zentro";
  private static final String AUDIENCE = "zentro-api";

  private static RSAKey trustedRsaKey;
  private static RSAKey untrustedRsaKey;
  private static HttpServer jwksServer;
  private static String jwksUri;

  @Autowired private MockMvc mockMvc;

  @MockBean private UserProfileService userProfileService;
  @MockBean private MerchantApplicationService merchantApplicationService;

  @BeforeAll
  static void startJwksServer() throws Exception {
    trustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s1-trusted-kid").generate();
    untrustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s1-trusted-kid").generate();

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

  // Helper token generators
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

  // 1. Positive Tests
  @Test
  @DisplayName("Valid RS256 token with CUSTOMER role can access own profile")
  void customerToken_usersMeEndpoint_returns2xx() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("CUSTOMER")))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  @DisplayName("Valid RS256 token with DISPATCHER role can access admin user list")
  void dispatcherToken_adminUsersEndpoint_returns2xx() throws Exception {
    when(userProfileService.listUsers(any(), any())).thenReturn(List.of());
    mockMvc.perform(get("/api/v1/admin/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("DISPATCHER")))
        .andExpect(status().is2xxSuccessful());
  }

  @Test
  @DisplayName("Valid RS256 token with CUSTOMER role can submit merchant application")
  void customerToken_merchantApply_returns2xx() throws Exception {
    mockMvc.perform(post("/api/v1/merchants")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("CUSTOMER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessName\":\"Acme\",\"businessRegNo\":\"BR-1\",\"contactPhone\":\"+94771234567\"}"))
        .andExpect(status().is2xxSuccessful());
  }

  // 2. Negative Tests - Missing JWT
  @Test
  @DisplayName("Missing JWT is rejected with 401")
  void missingJwt_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));

    mockMvc.perform(get("/api/v1/admin/users"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 3. Negative Tests - Malformed JWT
  @Test
  @DisplayName("Malformed JWT string is rejected with 401")
  void malformedJwt_returns401() throws Exception {
    for (String badToken : List.of("not-a-jwt", "abc.def", "header.payload.signature.extra")) {
      mockMvc.perform(get("/api/v1/users/me")
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + badToken))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.detail").value("Authentication required"));
    }
  }

  // 4. Negative Tests - Expired JWT
  @Test
  @DisplayName("Expired JWT is rejected with 401")
  void expiredToken_returns401() throws Exception {
    String token = signToken(
        trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120), List.of("CUSTOMER"));
    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 5. Negative Tests - Invalid Signature (signed by untrusted key)
  @Test
  @DisplayName("Invalid signature signed by untrusted RSA key is rejected with 401")
  void invalidSignature_returns401() throws Exception {
    String token = signToken(
        untrustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("CUSTOMER"));
    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 6. Negative Tests - Tampered JWT
  @Test
  @DisplayName("Tampered JWT payload with forged claims is rejected with 401")
  void tamperedJwtPayload_returns401() throws Exception {
    String valid = validToken("CUSTOMER");
    String[] parts = valid.split("\\.");
    String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        "{\"sub\":\"attacker\",\"realm_access\":{\"roles\":[\"DISPATCHER\"]}}".getBytes(StandardCharsets.UTF_8));
    String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tamperedToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 7. Negative Tests - Untrusted Issuer
  @Test
  @DisplayName("JWT with untrusted issuer is rejected with 401")
  void untrustedIssuer_returns401() throws Exception {
    String token = signToken(
        trustedRsaKey, "http://evil-issuer.local/realms/zentro", List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("CUSTOMER"));
    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 8. Negative Tests - Incorrect or Missing Required Audience
  @Test
  @DisplayName("JWT with incorrect audience or missing audience is rejected with 401")
  void incorrectOrMissingAudience_returns401() throws Exception {
    String wrongAudToken = signToken(
        trustedRsaKey, ISSUER, List.of("wrong-audience"), Instant.now().plusSeconds(300), List.of("CUSTOMER"));
    String missingAudToken = signToken(
        trustedRsaKey, ISSUER, null, Instant.now().plusSeconds(300), List.of("CUSTOMER"));

    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));

    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + missingAudToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 9. Negative Tests - Unsupported Signing Algorithm
  @Test
  @DisplayName("JWT with unsupported symmetric algorithm HS256 is rejected with 401")
  void unsupportedAlgorithmHs256_returns401() throws Exception {
    String token = hmacToken("secret01234567890123456789012345678901", ISSUER, List.of(AUDIENCE), List.of("CUSTOMER"));
    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  @Test
  @DisplayName("Unsigned token with alg:none is rejected with 401")
  void unsignedTokenAlgNone_returns401() throws Exception {
    String token = unsignedToken(ISSUER, List.of(AUDIENCE), List.of("CUSTOMER"));
    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 10. Negative Tests - Direct Service Access with Forged Gateway Headers
  @Test
  @DisplayName("Direct service access with forged gateway headers but no token is rejected with 401")
  void forgedGatewayHeadersWithoutToken_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/users/me")
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", "attacker-1")
            .header("X-User-Roles", "ROLE_DISPATCHER"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 11. Negative Tests - Gateway-Approved Request Carrying Invalid Token
  @Test
  @DisplayName("Gateway-forwarded request carrying an invalid or expired token is rejected by downstream service")
  void gatewayForwardedRequestWithInvalidToken_returns401() throws Exception {
    String expiredToken = signToken(
        trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120), List.of("CUSTOMER"));

    mockMvc.perform(get("/api/v1/users/me")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", "customer-1")
            .header("X-User-Roles", "ROLE_CUSTOMER")
            .header("X-Correlation-Id", "gateway-trace-123"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
  }

  // 12. Negative Tests - Role Boundaries
  @Test
  @DisplayName("CUSTOMER role cannot access admin user list (403 Forbidden)")
  void customerToken_adminUsersEndpoint_returns403() throws Exception {
    mockMvc.perform(get("/api/v1/admin/users")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("CUSTOMER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied"));
  }

  @Test
  @DisplayName("DISPATCHER role cannot submit merchant application (403 Forbidden)")
  void dispatcherToken_merchantApply_returns403() throws Exception {
    mockMvc.perform(post("/api/v1/merchants")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("DISPATCHER"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"businessName\":\"Acme\",\"businessRegNo\":\"BR-1\",\"contactPhone\":\"+94771234567\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied"));
  }
}
