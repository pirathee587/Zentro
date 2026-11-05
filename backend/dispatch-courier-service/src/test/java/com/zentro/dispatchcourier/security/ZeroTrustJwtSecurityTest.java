package com.zentro.dispatchcourier.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.zentro.dispatchcourier.courier.controller.CourierDeliveryController;
import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.courier.service.ProofOfDeliveryService;
import com.zentro.dispatchcourier.dispatch.controller.DispatchQueueController;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.dispatch.service.DispatchService;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
import com.zentro.dispatchcourier.exception.DispatchExceptionHandler;
import com.zentro.dispatchcourier.exception.ProofExceptionHandler;
import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end Zero Trust JWT validation tests exercising the real {@link ZentroSecurityAutoConfiguration}
 * {@code JwtDecoder} (RS256 signature verification against JWKS, issuer, expiration, audience, and
 * {@code realm_access.roles} mapping) together with {@link DispatchCourierSecurityConfig}.
 */
@WebMvcTest(controllers = {DispatchQueueController.class, CourierDeliveryController.class})
@Import({
    ZentroSecurityAutoConfiguration.class,
    DispatchCourierSecurityConfig.class,
    DispatchService.class,
    ProofOfDeliveryService.class,
    DispatchExceptionHandler.class,
    ProofExceptionHandler.class
})
class ZeroTrustJwtSecurityTest {

  private static final String ISSUER = "http://localhost:8180/realms/zentro";
  private static final String AUDIENCE = "zentro-api";
  private static final RSAKey TRUSTED_KEY;
  private static final RSAKey UNTRUSTED_KEY;
  private static final HttpServer JWKS_SERVER;
  private static final String JWKS_URI;

  static {
    try {
      TRUSTED_KEY = new RSAKeyGenerator(2048).keyID("zentro-key-1").generate();
      UNTRUSTED_KEY = new RSAKeyGenerator(2048).keyID("zentro-key-1").generate();
      JWKS_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] jwksBytes = new JWKSet(TRUSTED_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      JWKS_SERVER.createContext("/certs", exchange -> {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, jwksBytes.length);
        exchange.getResponseBody().write(jwksBytes);
        exchange.close();
      });
      JWKS_SERVER.start();
      JWKS_URI = "http://127.0.0.1:" + JWKS_SERVER.getAddress().getPort() + "/certs";
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @DynamicPropertySource
  static void securityProperties(DynamicPropertyRegistry registry) {
    registry.add("zentro.security.issuer-uri", () -> ISSUER);
    registry.add("zentro.security.audience", () -> AUDIENCE);
    registry.add("zentro.security.jwks-uri", () -> JWKS_URI);
  }

  @AfterAll
  static void stopJwksServer() {
    JWKS_SERVER.stop(0);
  }

  @Autowired MockMvc mvc;
  @MockBean ParcelSource parcelSource;
  @MockBean AssignmentRepository assignmentRepository;
  @MockBean ProofRepository proofRepository;
  @MockBean IdentityCourierClient identityCourierClient;

  @BeforeEach
  void stubDependencies() {
    when(parcelSource.eligibleParcels(anyString()))
        .thenReturn(new ParcelSource.Snapshot(List.of(), true));
    when(assignmentRepository.assignedIds(any())).thenReturn( java.util.Set.of());
    UUID profileId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(identityCourierClient.currentCourier(anyString()))
        .thenReturn(new IdentityCourierClient.Courier(profileId, userId, "Courier", true, "ACTIVE"));
    when(proofRepository.deliveries(profileId, userId)).thenReturn(List.of());
  }

  @Test
  void validSignedDispatcherTokenAccessesDispatchEndpoint() throws Exception {
    String token = signToken(TRUSTED_KEY, ISSUER, List.of(AUDIENCE),
        Instant.now().plusSeconds(300), List.of("DISPATCHER"));

    mvc.perform(get("/api/v1/dispatch/queue")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.complete").value(true));
  }

  @Test
  void validSignedCourierTokenAccessesCourierDeliveriesEndpoint() throws Exception {
    String token = signToken(TRUSTED_KEY, ISSUER, List.of(AUDIENCE),
        Instant.now().plusSeconds(300), List.of("COURIER"));

    mvc.perform(get("/api/v1/courier/deliveries")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
  }

  @Test
  void invalidSignatureIsRejectedWith401() throws Exception {
    String badSigToken = signToken(UNTRUSTED_KEY, ISSUER, List.of(AUDIENCE),
        Instant.now().plusSeconds(300), List.of("DISPATCHER", "COURIER"));

    mvc.perform(get("/api/v1/dispatch/queue")
            .header("Authorization", "Bearer " + badSigToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    mvc.perform(get("/api/v1/courier/deliveries")
            .header("Authorization", "Bearer " + badSigToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(parcelSource, assignmentRepository, proofRepository, identityCourierClient);
  }

  @Test
  void expiredTokenIsRejectedWith401() throws Exception {
    String expiredToken = signToken(TRUSTED_KEY, ISSUER, List.of(AUDIENCE),
        Instant.now().minusSeconds(600), List.of("DISPATCHER", "COURIER"));

    mvc.perform(get("/api/v1/dispatch/queue")
            .header("Authorization", "Bearer " + expiredToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    mvc.perform(get("/api/v1/courier/deliveries")
            .header("Authorization", "Bearer " + expiredToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(parcelSource, assignmentRepository, proofRepository, identityCourierClient);
  }

  @Test
  void wrongIssuerIsRejectedWith401() throws Exception {
    String wrongIssuerToken = signToken(TRUSTED_KEY, "http://evil.example.com/realms/zentro",
        List.of(AUDIENCE), Instant.now().plusSeconds(300), List.of("DISPATCHER", "COURIER"));

    mvc.perform(get("/api/v1/dispatch/queue")
            .header("Authorization", "Bearer " + wrongIssuerToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    mvc.perform(get("/api/v1/courier/deliveries")
            .header("Authorization", "Bearer " + wrongIssuerToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(parcelSource, assignmentRepository, proofRepository, identityCourierClient);
  }

  @Test
  void wrongOrMissingAudienceIsRejectedWith401() throws Exception {
    String wrongAudienceToken = signToken(TRUSTED_KEY, ISSUER,
        List.of("wrong-audience"), Instant.now().plusSeconds(300), List.of("DISPATCHER", "COURIER"));
    String missingAudienceToken = signToken(TRUSTED_KEY, ISSUER,
        null, Instant.now().plusSeconds(300), List.of("DISPATCHER", "COURIER"));

    for (String token : List.of(wrongAudienceToken, missingAudienceToken)) {
      mvc.perform(get("/api/v1/dispatch/queue")
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

      mvc.perform(get("/api/v1/courier/deliveries")
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    verifyNoInteractions(parcelSource, assignmentRepository, proofRepository, identityCourierClient);
  }

  @Test
  void directAccessWithoutJwtOrWithForgedGatewayHeadersIsRejectedWith401() throws Exception {
    mvc.perform(get("/api/v1/dispatch/queue")
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", "dispatcher-1")
            .header("X-User-Roles", "ROLE_DISPATCHER"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    mvc.perform(get("/api/v1/courier/deliveries")
            .header("X-Forwarded-For", "10.0.0.1")
            .header("X-User-Id", "courier-1")
            .header("X-User-Roles", "ROLE_COURIER"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(parcelSource, assignmentRepository, proofRepository, identityCourierClient);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER", "SERVICE"})
  void insufficientRoleForDispatchIsRejectedWith403(String role) throws Exception {
    String token = signToken(TRUSTED_KEY, ISSUER, List.of(AUDIENCE),
        Instant.now().plusSeconds(300), List.of(role));

    mvc.perform(get("/api/v1/dispatch/queue")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    mvc.perform(post("/api/v1/dispatch/assignments")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"parcelId\":\"" + UUID.randomUUID() + "\",\"courierId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(parcelSource, assignmentRepository, identityCourierClient);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CUSTOMER", "MERCHANT", "DISPATCHER", "SERVICE"})
  void insufficientRoleForCourierDeliveriesIsRejectedWith403(String role) throws Exception {
    String token = signToken(TRUSTED_KEY, ISSUER, List.of(AUDIENCE),
        Instant.now().plusSeconds(300), List.of(role));

    mvc.perform(get("/api/v1/courier/deliveries")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    mvc.perform(post("/api/v1/courier/deliveries/" + UUID.randomUUID() + "/proof")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"recipientName\":\"Nimal\",\"proofReference\":\"REF-1\"}"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(proofRepository, assignmentRepository, identityCourierClient);
  }

  private static String signToken(
      RSAKey signingKey,
      String issuer,
      List<String> audience,
      Instant expiresAt,
      List<String> roles) throws Exception {
    JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
        .subject("subject-" + UUID.randomUUID())
        .issuer(issuer)
        .issueTime(Date.from(expiresAt.minusSeconds(300)))
        .expirationTime(Date.from(expiresAt))
        .claim("realm_access", Map.of("roles", roles));
    if (audience != null) {
      claims.audience(audience);
    }
    SignedJWT signedJwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
        claims.build());
    signedJwt.sign(new RSASSASigner(signingKey));
    return signedJwt.serialize();
  }
}

