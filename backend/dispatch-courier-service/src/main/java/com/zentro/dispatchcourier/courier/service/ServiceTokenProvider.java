package com.zentro.dispatchcourier.courier.service;

import com.zentro.dispatchcourier.exception.CompletionFailure;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Confidential client credentials stay server-side. Never uses a human token. */
@Component
public class ServiceTokenProvider {
  private final RestClient client;
  private final String tokenUri;
  private final String clientId;
  private final String clientSecret;
  private String cachedToken;
  private Instant validUntil = Instant.EPOCH;

  @org.springframework.beans.factory.annotation.Autowired
  public ServiceTokenProvider(RestClient.Builder builder,
      @Value("${zentro.pod.token-uri:}") String tokenUri,
      @Value("${zentro.pod.client-id:}") String clientId,
      @Value("${zentro.pod.client-secret:}") String clientSecret) {
    this(builder.requestFactory(httpFactory()).build(), tokenUri, clientId, clientSecret);
  }
  public ServiceTokenProvider(RestClient client, String tokenUri, String clientId, String clientSecret) {
    this.client = client;
    this.tokenUri = tokenUri;
    this.clientId = clientId;
    this.clientSecret = clientSecret;
  }

  static JdkClientHttpRequestFactory httpFactory() {
    var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(java.time.Duration.ofSeconds(5));
    return factory;
  }

  public synchronized String token() {
    if (cachedToken != null && Instant.now().isBefore(validUntil)) return cachedToken;
    if (tokenUri.isBlank() || clientId.isBlank() || clientSecret.isBlank())
      throw new CompletionFailure("BLOCKED", "SERVICE_AUTH_NOT_CONFIGURED");
    var form = new LinkedMultiValueMap<String, String>() {
      @Override public String toString() { return "[client credentials redacted]"; }
    };
    form.add("grant_type", "client_credentials");
    form.add("client_id", clientId);
    form.add("client_secret", clientSecret);
    try {
      Map<?, ?> body = client.post().uri(tokenUri).contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form).retrieve().body(Map.class);
      if (body == null || !(body.get("access_token") instanceof String accessToken) || accessToken.isBlank()
          || !(body.get("expires_in") instanceof Number expires) || expires.longValue() <= 0)
        throw new CompletionFailure("BLOCKED", "INVALID_TOKEN_RESPONSE");
      cachedToken = accessToken;
      validUntil = Instant.now().plusSeconds(Math.max(0, Math.min(expires.longValue(), 3600) - 30));
      return cachedToken;
    } catch (RestClientResponseException error) {
      int status = error.getStatusCode().value();
      throw new CompletionFailure(status == 429 || status >= 500 ? "PENDING" : "BLOCKED", "TOKEN_REQUEST_FAILED");
    } catch (RestClientException error) {
      throw new CompletionFailure("PENDING", "TOKEN_SERVICE_UNAVAILABLE");
    } catch (IllegalArgumentException error) {
      throw new CompletionFailure("BLOCKED", "INVALID_TOKEN_CONFIGURATION");
    }
  }
  public synchronized void invalidate() { cachedToken = null; validUntil = Instant.EPOCH; }
}
