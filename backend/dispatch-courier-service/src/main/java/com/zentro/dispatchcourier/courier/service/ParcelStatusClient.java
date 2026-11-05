package com.zentro.dispatchcourier.courier.service;

import com.zentro.dispatchcourier.exception.CompletionFailure;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ParcelStatusClient {
  private final RestClient client;
  private final ServiceTokenProvider tokens;
  @org.springframework.beans.factory.annotation.Autowired
  public ParcelStatusClient(RestClient.Builder builder, ServiceTokenProvider tokens,
      @Value("${zentro.pod.parcel-base-url:http://localhost:8082}") String baseUrl) {
    this(builder.baseUrl(baseUrl).requestFactory(ServiceTokenProvider.httpFactory()).build(), tokens);
  }
  public ParcelStatusClient(RestClient client, ServiceTokenProvider tokens) {
    this.client = client;
    this.tokens = tokens;
  }
  public void complete(UUID parcelId) {
    try {
      Map<?, ?> response = client.patch().uri("/api/v1/parcels/{id}/status", parcelId)
          .headers(headers -> headers.setBearerAuth(tokens.token()))
          .body(Map.of("status", "DELIVERED")).retrieve().body(Map.class);
      if (response == null || !parcelId.toString().equals(response.get("parcelId"))
          || !"DELIVERED".equals(response.get("status")) || !(response.get("changed") instanceof Boolean))
        throw new CompletionFailure("BLOCKED", "INVALID_STATUS_RESPONSE");
    } catch (RestClientResponseException error) {
      int status = error.getStatusCode().value();
      if (status == 401) tokens.invalidate();
      if (status == 409) throw new CompletionFailure("REJECTED", "DELIVERY_TRANSITION_REJECTED");
      if (status == 429 || status >= 500) throw new CompletionFailure("PENDING", "PARCEL_SERVICE_UNAVAILABLE");
      throw new CompletionFailure("BLOCKED", "PARCEL_REQUEST_REJECTED");
    } catch (RestClientException error) {
      throw new CompletionFailure("PENDING", "PARCEL_SERVICE_UNAVAILABLE");
    } catch (IllegalArgumentException error) {
      throw new CompletionFailure("BLOCKED", "INVALID_PARCEL_CONFIGURATION");
    }
  }
}
