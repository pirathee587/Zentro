package com.zentro.parcelpricing.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.contracts.identity.v1.MerchantVerificationResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies merchant approval and account status via S1 gRPC ({@link IdentityPartnerGrpcClient}).
 */
@Component
public class IdentityMerchantClient {
  private static final Logger log = LoggerFactory.getLogger(IdentityMerchantClient.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final IdentityPartnerGrpcClient grpcClient;

  public IdentityMerchantClient(IdentityPartnerGrpcClient grpcClient) {
    this.grpcClient = grpcClient;
  }

  public void requireApprovedMerchant(String bearerToken) {
    String subject = resolveSubject(bearerToken);
    MerchantVerificationResponse response = grpcClient.verifyMerchantAccount(subject);
    if (response == null || !response.getApproved()) {
      log.debug("Merchant approval check failed: accountStatus={}, merchantStatus={}",
          response != null ? response.getAccountStatus() : "null",
          response != null ? response.getMerchantStatus() : "null");
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Approved merchant account is required");
    }
  }

  private static String resolveSubject(String bearerToken) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      if (bearerToken == null || bearerToken.isBlank() || bearerToken.equals(jwt.getTokenValue())) {
        if (jwt.getSubject() != null && !jwt.getSubject().isBlank()) {
          return jwt.getSubject();
        }
      }
    }
    if (bearerToken == null || bearerToken.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing merchant authentication token");
    }
    String[] parts = bearerToken.split("\\.");
    if (parts.length < 2) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid merchant authentication token");
    }
    try {
      byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
      JsonNode payload = JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
      String sub = payload.path("sub").asText("");
      if (sub.isBlank()) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing subject claim in merchant token");
      }
      return sub;
    } catch (IllegalArgumentException | java.io.IOException e) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Malformed merchant authentication token", e);
    }
  }
}
