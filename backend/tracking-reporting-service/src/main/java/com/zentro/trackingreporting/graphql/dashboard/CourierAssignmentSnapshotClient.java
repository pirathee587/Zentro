package com.zentro.trackingreporting.graphql.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class CourierAssignmentSnapshotClient {
  public record Assignment(UUID parcelId, Instant assignedAt, String assignmentState,
      UUID proofId, String completionState, Instant completedAt) {}
  private final RestClient client;
  public CourierAssignmentSnapshotClient(RestClient.Builder builder,
      @Value("${zentro.dashboard.dispatch-base-url:http://localhost:8083}") String baseUrl) {
    client = builder.baseUrl(baseUrl).requestFactory(httpFactory()).build();
  }

  public List<Assignment> snapshot(String courierToken) {
    try {
      var result = client.get().uri("/internal/courier-dashboard/assignments")
          .headers(headers -> headers.setBearerAuth(courierToken)).retrieve().body(new ParameterizedTypeReference<List<Assignment>>() {});
      var seen = new HashSet<UUID>();
      if (result == null || result.size() > 5000 || result.stream().anyMatch(row -> row == null
          || row.parcelId() == null || row.assignedAt() == null || !"ASSIGNED".equals(row.assignmentState())
          || !seen.add(row.parcelId()))) throw unavailable();
      return List.copyOf(result);
    } catch (RestClientResponseException failure) {
      String code = problemCode(failure);
      if (failure.getStatusCode().value() == 401 && "COURIER_UNAUTHENTICATED".equals(code))
        throw new AuthenticationCredentialsNotFoundException("Your courier session could not be verified.");
      if (failure.getStatusCode().value() == 403 && "COURIER_ACCESS_DENIED".equals(code))
        throw new AccessDeniedException("Courier access could not be verified.");
      if (failure.getStatusCode().value() == 404 && "COURIER_PROFILE_NOT_FOUND".equals(code))
        throw new CourierDashboardException("COURIER_PROFILE_NOT_FOUND");
      throw unavailable();
    } catch (RestClientException | IllegalArgumentException failure) { throw unavailable(); }
  }

  private static String problemCode(RestClientResponseException failure) {
    try {
      var body = failure.getResponseBodyAs(java.util.Map.class);
      return body != null && body.get("code") instanceof String code ? code : "";
    } catch (RuntimeException malformedProblem) { return ""; }
  }

  static JdkClientHttpRequestFactory httpFactory() {
    var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(8));
    return factory;
  }
  private static CourierDashboardException unavailable() {
    return new CourierDashboardException("ASSIGNMENT_SNAPSHOT_UNAVAILABLE");
  }
}
