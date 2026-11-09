package com.zentro.trackingreporting.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import com.zentro.trackingreporting.exception.GlobalTrackingExceptionHandler;
import com.zentro.trackingreporting.tracking.controller.CustomerTrackingController;
import com.zentro.trackingreporting.tracking.controller.PublicTrackingController;
import com.zentro.trackingreporting.tracking.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import com.zentro.trackingreporting.tracking.service.TrackingStreamService;
import java.io.OutputStream;
import java.math.BigDecimal;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies Zero Trust JWT authentication and authorization in S4 (tracking-reporting-service)
 * using the real {@link TrackingSecurityConfig} filter chain, the shared JWT decoder from
 * {@link ZentroSecurityAutoConfiguration}, and RS256-signed tokens validated against a local
 * JWKS server (no mocked {@code JwtDecoder}).
 */
@WebMvcTest(controllers = {CustomerTrackingController.class, PublicTrackingController.class})
@Import({
    TrackingSecurityConfig.class,
    ZentroSecurityAutoConfiguration.class,
    GlobalTrackingExceptionHandler.class
})
class ZeroTrustJwtSecurityTest {
  private static final String ISSUER = "http://localhost:8180/realms/zentro";
  private static final String AUDIENCE = "zentro-api";
  private static final String MINE = "/api/v1/tracking/mine";

  private static RSAKey trustedRsaKey;
  private static RSAKey untrustedRsaKey;
  private static HttpServer jwksServer;
  private static String jwksUri;
  private static final RSAKey trustedRsaKey;
  private static final RSAKey untrustedRsaKey;
  private static final HttpServer jwksServer;
  private static final String jwksUri;

  static {
    try {
      trustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s4-trusted-kid").generate();
      // Same kid, different key material: simulates a forged token.
      untrustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s4-trusted-kid").generate();

      byte[] jwksBytes = new JWKSet(trustedRsaKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      jwksServer.createContext("/realms/zentro/protocol/openid-connect/certs", exchange -> {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, jwksBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
          os.write(jwksBytes);
        }
        exchange.close();
      });
      jwksServer.start();
      jwksUri = "http://127.0.0.1:" + jwksServer.getAddress().getPort()
          + "/realms/zentro/protocol/openid-connect/certs";
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @Autowired private MockMvc mockMvc;
  @MockBean private TrackingService trackingService;
  @MockBean private TrackingStreamService streamService;

  @BeforeAll
  static void startJwksServer() throws Exception {
    trustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s4-trusted-kid").generate();
    // Same kid, different key material: simulates a forged token.
    untrustedRsaKey = new RSAKeyGenerator(2048).keyID("zentro-s4-trusted-kid").generate();

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
      RSAKey signingKey, String issuer, List<String> audience, Instant expiresAt, String subject, List<String> roles)
      throws Exception {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .subject(subject)
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

  private static String validToken(String subject, String... roles) throws Exception {
    return signToken(trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300), subject, List.of(roles));
  }

  private static CustomerDeliveryListResponse emptyPage() {
    return new CustomerDeliveryListResponse(List.of(), List.of(), 0, 0, 0, 1, 10, BigDecimal.ZERO, "LKR 0.00");
  }

  @Test
  @DisplayName("Valid RS256 JWT with ROLE_CUSTOMER reads own deliveries, scoped to the token subject")
  void acceptsValidCustomerTokenAndScopesToSubject() throws Exception {
    when(trackingService.getCustomerDeliveries(eq("customer-alice"), anyInt(), anyInt(), any(), any()))
        .thenReturn(emptyPage());

    mockMvc.perform(get(MINE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("customer-alice", "CUSTOMER"))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  @DisplayName("Valid RS256 JWT with ROLE_MERCHANT is also allowed on /mine")
  void acceptsValidMerchantToken() throws Exception {
    when(trackingService.getCustomerDeliveries(eq("merchant-1"), anyInt(), anyInt(), any(), any()))
        .thenReturn(emptyPage());

    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("merchant-1", "MERCHANT")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Invalid JWT signature signed by untrusted key is rejected with 401")
  void rejectsInvalidSignature() throws Exception {
    String token = signToken(untrustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().plusSeconds(300),
        "customer-alice", List.of("CUSTOMER"));

    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(trackingService);
  }

  @Test
  @DisplayName("Expired JWT is rejected with 401")
  void rejectsExpiredToken() throws Exception {
    String token = signToken(trustedRsaKey, ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(120),
        "customer-alice", List.of("CUSTOMER"));

    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(trackingService);
  }

  @Test
  @DisplayName("JWT with wrong issuer is rejected with 401")
  void rejectsWrongIssuer() throws Exception {
    String token = signToken(trustedRsaKey, "http://evil-issuer.local/realms/zentro", List.of(AUDIENCE),
        Instant.now().plusSeconds(300), "customer-alice", List.of("CUSTOMER"));

    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(trackingService);
  }

  @Test
  @DisplayName("JWT with wrong audience or missing audience claim is rejected with 401")
  void rejectsWrongOrMissingAudience() throws Exception {
    String wrongAud = signToken(trustedRsaKey, ISSUER, List.of("wrong-audience"), Instant.now().plusSeconds(300),
        "customer-alice", List.of("CUSTOMER"));
    String missingAud = signToken(trustedRsaKey, ISSUER, null, Instant.now().plusSeconds(300),
        "customer-alice", List.of("CUSTOMER"));

    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAud))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + missingAud))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(trackingService);
  }

  @Test
  @DisplayName("Direct S4 access without a token or with forged gateway headers is rejected with 401")
  void rejectsMissingAuthenticationAndForgedGatewayHeaders() throws Exception {
    mockMvc.perform(get(MINE))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));

    mockMvc.perform(get(MINE)
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", "customer-alice")
            .header("X-User-Roles", "ROLE_CUSTOMER"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.detail").value("Authentication required"));
    verifyNoInteractions(trackingService);
  }

  @Test
  @DisplayName("Valid JWT with insufficient role (COURIER / DISPATCHER) on /mine is rejected with 403")
  void rejectsInsufficientRole() throws Exception {
    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("courier-1", "COURIER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied"));
    mockMvc.perform(get(MINE).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("disp-1", "DISPATCHER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.detail").value("Access denied"));
    verifyNoInteractions(trackingService);
  }

  @Test
  @DisplayName("Public tracking lookup stays anonymous by design and exposes no personal data")
  void publicTrackingLookupIsAnonymous() throws Exception {
    String ref = "ZT-7K3QX9M2PA";
    when(trackingService.getPublicTracking(ref)).thenReturn(new PublicTrackingResponse(
        ref, "In progress", "IN_TRANSIT", "Small Box", "SMALL", null, "Colombo", "Kandy", 3,
        null, Instant.parse("2026-11-04T10:00:00Z"), null, null, List.of(), List.of()));

    mockMvc.perform(get("/api/v1/tracking/{ref}", ref).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.trackingRef").value(ref))
        .andExpect(jsonPath("$.fee").doesNotExist())
        .andExpect(jsonPath("$.sender").doesNotExist());
  }

  @Test
  @DisplayName("SERVICE-only token cannot access public tracking through the service directly")
  void serviceOnlyTokenIsRejectedFromPublicTracking() throws Exception {
    mockMvc.perform(get("/api/v1/tracking/{ref}", "ZT-7K3QX9M2PA")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("service-client", "SERVICE")))
        .andExpect(status().isForbidden());
    verifyNoInteractions(trackingService);
  }

  @Test
  @DisplayName("Live tracking stream without authentication is rejected with 401")
  void liveTrackingStreamWithoutAuthIsRejectedWith401() throws Exception {
    mockMvc.perform(get("/api/v1/tracking/ZT-7K3QX9M2PA/stream")
            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Live tracking stream with SERVICE role is rejected with 403")
  void liveTrackingStreamWithServiceRoleIsRejectedWith403() throws Exception {
    String token = validToken("service-daemon", "SERVICE");
    mockMvc.perform(get("/api/v1/tracking/ZT-7K3QX9M2PA/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Live tracking stream with expired token is rejected with 401")
  void liveTrackingStreamWithExpiredTokenIsRejectedWith401() throws Exception {
    String expiredToken = signToken(trustedRsaKey, ISSUER, List.of(AUDIENCE),
        Instant.now().minusSeconds(120), "customer-alice", List.of("CUSTOMER"));

    mockMvc.perform(get("/api/v1/tracking/ZT-7K3QX9M2PA/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + expiredToken)
            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Live tracking stream with valid customer token succeeds")
  void liveTrackingStreamWithValidCustomerTokenSucceeds() throws Exception {
    String token = validToken("customer-alice", "CUSTOMER");
    com.zentro.trackingreporting.tracking.entity.TrackingParcel parcel = new com.zentro.trackingreporting.tracking.entity.TrackingParcel();
    parcel.setTrackingRef("ZT-7K3QX9M2PA");
    parcel.setCustomerSubject("customer-alice");

    when(trackingService.getParcel("ZT-7K3QX9M2PA")).thenReturn(parcel);
    when(streamService.subscribe(eq("ZT-7K3QX9M2PA"), any(), any()))
        .thenReturn(new org.springframework.web.servlet.mvc.method.annotation.SseEmitter());

    mockMvc.perform(get("/api/v1/tracking/ZT-7K3QX9M2PA/stream")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted());
  }

  @Test
  @DisplayName("Unknown non-public tracking path still requires authentication")
  void nonPublicPathRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/tracking/" + UUID.randomUUID() + "/internal/history"))
        .andExpect(status().isUnauthorized());
  }
}

