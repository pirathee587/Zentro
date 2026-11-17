package com.zentro.dispatchcourier.dashboard;

import java.util.UUID;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Forwards only the authenticated courier token to S1's non-provisioning self lookup. */
@Component
public class CourierIdentityReadClient {
  public record Identity(UUID userId, UUID courierProfileId) {
    public UUID profileId() { return courierProfileId; }
  }
  private final RestClient client;
  public CourierIdentityReadClient(RestClient.Builder builder,
      @Value("${zentro.identity.base-url:http://localhost:8081}") String baseUrl) {
    var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var factory = new JdkClientHttpRequestFactory(http);
    factory.setReadTimeout(Duration.ofSeconds(5));
    client = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }
  public Identity resolve(String courierToken) {
    try {
      var identity = client.get().uri("/internal/courier-dashboard/identity")
          .headers(headers -> headers.setBearerAuth(courierToken)).retrieve().body(Identity.class);
      if (identity == null || identity.userId() == null || identity.profileId() == null) throw unavailable();
      return identity;
    } catch (RestClientResponseException failure) {
      var status = failure.getStatusCode();
      String code = problemCode(failure);
      if ((status.value() == 401 && "COURIER_UNAUTHENTICATED".equals(code))
          || (status.value() == 403 && "COURIER_ACCESS_DENIED".equals(code))
          || (status.value() == 404 && "COURIER_PROFILE_NOT_FOUND".equals(code)))
        throw new ResponseStatusException(status, "Courier identity could not be verified.");
      throw unavailable();
    } catch (RestClientException | IllegalArgumentException failure) { throw unavailable(); }
  }
  private static String problemCode(RestClientResponseException failure) {
    try {
      var body = failure.getResponseBodyAs(java.util.Map.class);
      return body != null && body.get("code") instanceof String code ? code : "";
    } catch (RuntimeException malformed) { return ""; }
  }
  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Courier identity could not be verified.");
  }
}
