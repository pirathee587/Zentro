package com.zentro.parcelpricing.assignment;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class AssignmentOutcomeClient {
  public record Outcome(UUID operationId, UUID parcelId, String actorSubject, String mode, String state) {
  }
  private final RestClient http;
  public AssignmentOutcomeClient(RestClient.Builder builder,
  @Value("${zentro.assignment.dispatch-base-url:http://localhost:8083}") String baseUrl) {
    var transport=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var factory=new JdkClientHttpRequestFactory(transport);
    factory.setReadTimeout(Duration.ofSeconds(5));
    http=builder.baseUrl(baseUrl).requestFactory(factory).build();
  }
  public Outcome outcome(UUID id,String token) {
    try {
      var result=http.get().uri("/internal/dispatch/assignment-operations/{id}",id)
      .headers(h->h.setBearerAuth(token)).retrieve().body(Outcome.class);
      if(result==null || !id.equals(result.operationId())) throw unavailable();
      return result;
    }
    catch(RestClientException | IllegalArgumentException failure) {
      throw unavailable();
    }
  }
  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Assignment outcome cannot be verified.");
  }
}
