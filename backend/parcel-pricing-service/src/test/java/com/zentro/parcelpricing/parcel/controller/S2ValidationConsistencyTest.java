package com.zentro.parcelpricing.parcel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zentro.parcelpricing.pricing.controller.ParcelPricingController;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.service.MerchantOrderService;
import com.zentro.parcelpricing.pricing.service.PricingService;
import com.zentro.parcelpricing.pricing.service.ZoneService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {ParcelBookingController.class, MerchantOrderController.class, ParcelPricingController.class})
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {"zentro.security.issuer-uri=http://localhost:8180/realms/zentro", "zentro.security.audience=zentro-api"})
class S2ValidationConsistencyTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockBean ParcelBookingService bookings;
  @MockBean MerchantOrderService orders;
  @MockBean PricingService pricing;
  @MockBean ZoneService zones;
  @MockBean JwtDecoder decoder;

  private ObjectNode body() throws Exception {
    return (ObjectNode) mapper.readTree("""
        {"externalOrderRef":"SYNTHETIC-124", "sender":{"name":"Synthetic Sender","phone":"0770000000","address":"Synthetic Road 01"},
        "recipient":{"name":"Synthetic Recipient","phone":"0710000000","address":"Synthetic Road 02"},
        "parcel":{"weightKg":1,"category":"Test","size":"SMALL"}}
        """);
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1", "0.09", "20.01", "100", "1.12345", "999999999999999999999999999999999999"})
  void sameInvalidNumericWeightHasEndpointSpecificFieldErrors(String value) throws Exception {
    for (String path : new String[]{"/api/v1/parcels", "/api/v1/merchant/orders", "/api/v1/parcels/estimate", "/api/v1/parcels/estimate-fee"}) {
      boolean estimate = path.contains("estimate");
      ObjectNode request = estimate ? mapper.createObjectNode() : body();
      (estimate ? request : (ObjectNode) request.get("parcel")).set("weightKg", mapper.readTree(value));
      int expected = value.length() > 32 ? 400 : estimate ? 400 : 422;
      var response = mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority(path.contains("merchant") ? "ROLE_MERCHANT" : "ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
          .andExpect(status().is(expected)).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
      if (value.length() <= 32) response.andExpect(jsonPath(estimate ? "$.fieldErrors.weightKg" : "$.fieldErrors['parcel.weightKg']").exists());
    }
    verifyNoInteractions(bookings, orders, pricing);
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"1\"", "1e2", "1E+2", "\"NaN\"", "\"Infinity\"", "true", "{}", "[]"})
  void unsafeNumericRepresentationsAreSafe400(String value) throws Exception {
    for (String path : new String[]{"/api/v1/parcels", "/api/v1/merchant/orders", "/api/v1/parcels/estimate", "/api/v1/parcels/estimate-fee"}) {
      String request = path.contains("estimate") ? "{\"weightKg\":" + value + "}"
          : mapper.writeValueAsString(body()).replace("\"weightKg\":1", "\"weightKg\":" + value);
      mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority(path.contains("merchant") ? "ROLE_MERCHANT" : "ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isBadRequest());
    }
    verifyNoInteractions(bookings, orders, pricing);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "       ", "1234567", "synthetic\raddress", "synthetic\naddress", "synthetic\taddress", "synthetic\u0000address", "synthetic\u202Eaddress", "synthetic\u200Baddress"})
  void bothContactsAndBookingEndpointsRejectUnsafeAddressesWithoutEcho(String value) throws Exception {
    for (String path : new String[]{"/api/v1/parcels", "/api/v1/merchant/orders"}) {
      ObjectNode request = body();
      for (String contact : new String[]{"sender", "recipient"}) ((ObjectNode)request.get(contact)).put("address", value);
      String result = mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority(path.contains("merchant") ? "ROLE_MERCHANT" : "ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
          .andExpect(status().isUnprocessableEntity())
          .andExpect(jsonPath("$.fieldErrors['sender.address']").exists())
          .andExpect(jsonPath("$.fieldErrors['recipient.address']").exists()).andReturn().getResponse().getContentAsString();
      assertThat(result).doesNotContain("Synthetic Sender", "Synthetic Recipient", "0770000000");
    }
    verifyNoInteractions(bookings, orders, pricing);
  }
}
