package com.zentro.dispatchcourier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/** Reuses Identity's dispatcher REST API and effective-active rule. No zone/vehicle inference. */
@Component
public class IdentityCourierClient {
  private final RestClient client;

  @Autowired
  public IdentityCourierClient(RestClient.Builder builder,
      @Value("${zentro.identity.base-url:http://localhost:8081}") String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(3000);
    factory.setReadTimeout(5000);
    this.client = builder.baseUrl(baseUrl).requestFactory(factory).build();
  }

  IdentityCourierClient(RestClient client) {
    this.client = client;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Courier(UUID id, UUID userId, String name, Boolean active, String accountStatus) {}

  public List<Courier> listEligible(String bearerToken) {
    try {
      List<Courier> couriers = client.get()
          .uri("/api/v1/admin/couriers?active=true")
          .headers(headers -> headers.setBearerAuth(bearerToken))
          .retrieve().body(new ParameterizedTypeReference<List<Courier>>() {});
      if (couriers == null || couriers.stream().anyMatch(courier -> !valid(courier))) {
        throw unavailable();
      }
      return couriers.stream().filter(IdentityCourierClient::eligible).toList();
    } catch (RestClientException ex) {
      throw translate(ex);
    }
  }

  /** Call immediately before assignment persistence; returns Identity's canonical profile UUID. */
  public Courier requireEligible(UUID courierId, String bearerToken) {
    if (courierId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Courier ID is required.");
    }
    try {
      Courier courier = client.get().uri("/api/v1/admin/couriers/{id}", courierId)
          .headers(headers -> headers.setBearerAuth(bearerToken))
          .retrieve().body(Courier.class);
      if (!valid(courier)) throw unavailable();
      if (!courierId.equals(courier.id()) && !courierId.equals(courier.userId())) throw unavailable();
      if (!eligible(courier)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "This courier is no longer eligible. Choose another courier.");
      }
      return courier;
    } catch (RestClientResponseException ex) {
      if (ex.getStatusCode().value() == 404) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier profile not found.");
      }
      throw translate(ex);
    } catch (RestClientException ex) {
      throw translate(ex);
    }
  }

  private static boolean valid(Courier courier) {
    return courier != null && courier.id() != null && courier.userId() != null
        && courier.active() != null && courier.accountStatus() != null;
  }

  private static boolean eligible(Courier courier) {
    return Boolean.TRUE.equals(courier.active()) && "ACTIVE".equalsIgnoreCase(courier.accountStatus());
  }

  private static ResponseStatusException translate(RestClientException error) {
    if (error instanceof RestClientResponseException response
        && (response.getStatusCode().value() == 401 || response.getStatusCode().value() == 403)) {
      return new ResponseStatusException(response.getStatusCode(), "Identity rejected courier access.");
    }
    return unavailable();
  }

  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Courier eligibility could not be verified. Try again.");
  }
}
