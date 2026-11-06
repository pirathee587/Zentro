package com.zentro.notificationintegration.webhook.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zentro.notificationintegration.webhook.exception.MerchantVerificationException;
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

/**
 * Checks with S1 Identity ({@code GET /api/v1/users/me}, called with the merchant's own token) that the caller is
 * an approved, active merchant: the same rule S2 applies to merchant orders. The token is only forwarded to S1 and
 * never logged.
 */
@Component
public class IdentityMerchantClient {
  private static final Logger log = LoggerFactory.getLogger(IdentityMerchantClient.class);

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record UserProfile(String accountStatus, String merchantStatus) {}

  private final RestClient client;

  @Autowired
  public IdentityMerchantClient(RestClient.Builder builder,
      @Value("${zentro.identity.base-url:http://localhost:8081}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  public IdentityMerchantClient(RestClient client) {
    this.client = client;
  }

  /**
   * @throws MerchantVerificationException 401 without a token, 403 when not approved or not active, 503 when S1
   *                                       cannot be reached or answers unexpectedly
   */
  public void requireApprovedMerchant(String bearerToken) {
    if (bearerToken == null || bearerToken.isBlank()) {
      throw new MerchantVerificationException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
          "The access token is missing.");
    }
    UserProfile profile;
    try {
      profile = client.get()
          .uri("/api/v1/users/me")
          .headers(headers -> headers.setBearerAuth(bearerToken))
          .retrieve()
          .body(UserProfile.class);
    } catch (RestClientResponseException ex) {
      int status = ex.getStatusCode().value();
      if (status == 401 || status == 403) {
        throw new MerchantVerificationException(HttpStatus.FORBIDDEN, "MERCHANT_NOT_APPROVED",
            "Merchant access denied.");
      }
      log.warn("Identity returned status {} during merchant check", status);
      throw unavailable();
    } catch (RestClientException ex) {
      log.warn("Identity unavailable during merchant check: {}", ex.getClass().getSimpleName());
      throw unavailable();
    }
    if (profile == null) {
      throw unavailable();
    }
    if (!"ACTIVE".equalsIgnoreCase(profile.accountStatus())) {
      throw new MerchantVerificationException(HttpStatus.FORBIDDEN, "MERCHANT_NOT_APPROVED",
          "Merchant user account is not active.");
    }
    if (!"APPROVED".equalsIgnoreCase(profile.merchantStatus())) {
      throw new MerchantVerificationException(HttpStatus.FORBIDDEN, "MERCHANT_NOT_APPROVED",
          "Merchant application is not approved.");
    }
  }

  private static MerchantVerificationException unavailable() {
    return new MerchantVerificationException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE",
        "Merchant verification is unavailable. Please try again.");
  }
}
