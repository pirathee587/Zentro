package com.zentro.trackingreporting.graphql;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.zentro.trackingreporting.graphql.service.DashboardPricingClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DashboardPricingClientTest {
  @Test void forwardsJwtToFixedGatewayQuotationEndpointAndUsesActualResponse() {
    var builder = RestClient.builder().baseUrl("http://gateway.invalid");
    var server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("http://gateway.invalid/api/v1/parcels/estimate"))
        .andExpect(header("Authorization", "Bearer synthetic-token"))
        .andExpect(jsonPath("$.weightKg").value(2.5))
        .andExpect(jsonPath("$.pickupCity").doesNotExist())
        .andRespond(withSuccess("{\"fee\":925.5,\"currency\":\"LKR\",\"formattedFee\":\"LKR 925.50\",\"estimatedDays\":3,\"breakdown\":{\"baseFee\":500,\"weightFee\":425.5}}", MediaType.APPLICATION_JSON));
    var client = new DashboardPricingClient(builder.build());
    var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "RS256").subject("alice").build();
    assertThat(client.estimate(jwt, null, null, 2.5, "OTHER_DISTRICT", "Medium").fee()).isEqualByComparingTo("925.5");
    assertThatThrownBy(() -> client.estimate(jwt, "Colombo", "Kandy", Double.NaN, "OTHER_DISTRICT", null)).isInstanceOf(IllegalArgumentException.class);
    server.verify();
  }
  @Test void failuresAreSanitizedAndNeverSubstitutePrices() {
    var builder = RestClient.builder().baseUrl("http://gateway.invalid");
    var server = MockRestServiceServer.bindTo(builder).build();
    server.expect(anything()).andRespond(withServerError().body("private upstream body"));
    var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "RS256").subject("alice").build();
    assertThatThrownBy(() -> new DashboardPricingClient(builder.build()).estimate(jwt,"Colombo","Kandy",2,"OTHER_DISTRICT",null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("temporarily unavailable")
        .hasMessageNotContaining("private upstream body");
    server.verify();
  }
}
