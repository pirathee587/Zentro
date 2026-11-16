package com.zentro.trackingreporting.graphql.service;

import com.zentro.trackingreporting.graphql.dto.DashboardFeeEstimate;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Read-only quotation through the gateway; S2 remains the pricing authority. */
@Component
public class DashboardPricingClient {
  private final RestClient client;
  @Autowired public DashboardPricingClient(RestClient.Builder builder, @Value("${zentro.dashboard.gateway-url:http://localhost:8080}") String gateway) {
    var factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(1000); factory.setReadTimeout(3000);
    this.client = builder.clone().baseUrl(gateway).requestFactory(factory).build();
  }
  public DashboardPricingClient(RestClient client) { this.client = client; }
  public DashboardFeeEstimate estimate(Jwt jwt, String pickupCity, String deliveryCity,
      double weightKg, String destinationType, String parcelCategory) {
    if ((pickupCity != null && (pickupCity.isBlank() || pickupCity.length() > 100))
        || (deliveryCity != null && (deliveryCity.isBlank() || deliveryCity.length() > 100))
        || !Double.isFinite(weightKg) || weightKg <= 0 || weightKg > 50
        || !("SAME_CITY".equals(destinationType) || "OTHER_DISTRICT".equals(destinationType))
        || (parcelCategory != null && parcelCategory.length() > 100)) throw new IllegalArgumentException("Invalid quotation parameters");
    var body = new LinkedHashMap<String, Object>();
    if (pickupCity != null) body.put("pickupCity", pickupCity);
    if (deliveryCity != null) body.put("deliveryCity", deliveryCity);
    body.put("weightKg", BigDecimal.valueOf(weightKg)); body.put("destinationType", destinationType);
    if (parcelCategory != null) body.put("parcelCategory", parcelCategory);
    try {
      var result = client.post().uri("/api/v1/parcels/estimate")
          .header("Authorization", "Bearer " + jwt.getTokenValue()).body(body).retrieve().body(DashboardFeeEstimate.class);
      if (result == null) throw new IllegalArgumentException("Pricing response unavailable");
      return result;
    } catch (RestClientException ex) {
      // Never expose/log provider bodies, tokens or internal addresses.
      throw new IllegalArgumentException("Delivery pricing is temporarily unavailable. Please retry.");
    }
  }
}
