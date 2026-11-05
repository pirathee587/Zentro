package com.zentro.parcelpricing.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates merchant approval and account active state with Identity Partner Service (S1).
 */
@Component
public class IdentityMerchantClient {
  private static final Logger log = LoggerFactory.getLogger(IdentityMerchantClient.class);

  private final RestClient client;

  @Autowired
  public IdentityMerchantClient(
      RestClient.Builder builder,
      @Value("${zentro.identity.base-url:http://localhost:8081}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  public IdentityMerchantClient(RestClient client) {
    this.client = client;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record UserProfile(
      UUID id,
      String email,
      String name,
      String phone,
      String accountStatus,
      String merchantStatus,
      List<String> roles
  ) {}

  /**
   * Requires that the authenticated merchant has an APPROVED application and an ACTIVE account.
   */
  public void requireApprovedMerchant(String bearerToken) {
    if (bearerToken == null || bearerToken.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The access token is missing or empty.");
    }
    try {
      UserProfile profile = client.get()
          .uri("/api/v1/users/me")
          .headers(headers -> headers.setBearerAuth(bearerToken))
          .retrieve()
          .body(UserProfile.class);

      if (profile == null) {
        log.warn("Identity returned an empty profile response for merchant verification.");
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity verification service is unavailable. Please try again.");
      }
      if (!"ACTIVE".equalsIgnoreCase(profile.accountStatus())) {
        log.warn("Merchant account status is not active: {}", profile.accountStatus());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant user account is not active.");
      }
      if (!"APPROVED".equalsIgnoreCase(profile.merchantStatus())) {
        log.warn("Merchant application status is not approved: {}", profile.merchantStatus());
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant application is not approved.");
      }
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() == 401) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required or credentials invalid.");
      }
      if (ex.getStatusCode().value() == 403) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant access denied.");
      }
      log.error("Identity returned unexpected status during merchant check: {}", ex.getStatusCode());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity verification service is unavailable. Please try again.");
    } catch (RestClientException ex) {
      log.error("Failed to connect to Identity service during merchant check: {}", ex.getMessage());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity verification service is unavailable. Please try again.");
    }
  }
}
