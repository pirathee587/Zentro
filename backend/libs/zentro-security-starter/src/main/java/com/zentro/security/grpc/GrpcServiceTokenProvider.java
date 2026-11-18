package com.zentro.security.grpc;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Reusable OAuth2 Client Credentials token provider for backend-to-backend gRPC calls.
 * Caches short-lived SERVICE JWTs in memory and refreshes before expiration.
 */
public class GrpcServiceTokenProvider {
  private final RestClient client;
  private final String tokenUri;
  private final String clientId;
  private final String clientSecret;
  private String cachedToken;
  private Instant validUntil = Instant.EPOCH;

  public GrpcServiceTokenProvider(String tokenUri, String clientId, String clientSecret) {
    var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(5));
    this.client = RestClient.builder().requestFactory(factory).build();
    this.tokenUri = tokenUri == null ? "" : tokenUri;
    this.clientId = clientId == null ? "" : clientId;
    this.clientSecret = clientSecret == null ? "" : clientSecret;
  }

  public synchronized String token() {
    if (cachedToken != null && Instant.now().isBefore(validUntil)) {
      return cachedToken;
    }
    if (tokenUri.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
      throw new IllegalStateException("Service OAuth2 client credentials are not configured.");
    }
    var form = new LinkedMultiValueMap<String, String>() {
      @Override public String toString() { return "[client credentials redacted]"; }
    };
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);

    Map<?, ?> body = client.post().uri(tokenUri)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .body(form)
        .retrieve()
        .body(Map.class);
    if (body == null || !(body.get("access_token") instanceof String accessToken) || accessToken.isBlank()
        || !(body.get("expires_in") instanceof Number expires) || expires.longValue() <= 0) {
      throw new IllegalStateException("Invalid OAuth2 token response from identity provider.");
    }
    cachedToken = accessToken;
    validUntil = Instant.now().plusSeconds(Math.max(0, Math.min(expires.longValue(), 3600) - 30));
    return cachedToken;
  }

  public synchronized void invalidate() {
    cachedToken = null;
    validUntil = Instant.EPOCH;
  }
}
