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
  @MockBean com.zentro.parcelpricing.integration.IdentityMerchantClient identity;

  private ObjectNode body() throws Exception {
    return (ObjectNode) mapper.readTree("""
        {"externalOrderRef":"SYNTHETIC-124", "sender":{"name":"Synthetic Sender","phone":"0770000000","address":"Synthetic Road 01"},
        "recipient":{"name":"Synthetic Recipient","phone":"0710000000","address":"Synthetic Road 02"},
        "parcel":{"weightKg":1,"category":"Test","size":"SMALL"}}
        """);
  }

  @org.junit.jupiter.api.Test
  void httpTextBoundaryMatrixForBothContactEndpointsAndQuoteAliases() throws Exception {
    org.mockito.Mockito.when(bookings.book(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.nullable(String.class)))
        .thenReturn(new ParcelBookingService.BookingResult(null, false));
    org.mockito.Mockito.when(orders.createOrder(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(new MerchantOrderService.OrderResult(null, false));
    org.mockito.Mockito.when(pricing.estimateFee(org.mockito.ArgumentMatchers.any())).thenAnswer(inv ->
        new com.zentro.parcelpricing.pricing.rules.PricingCalculationEngine().calculate(inv.getArgument(0), com.zentro.parcelpricing.pricing.rules.PricingRule.defaultStandard()));
    org.mockito.Mockito.when(zones.isValidZone(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    for (String path : new String[]{"/api/v1/parcels", "/api/v1/merchant/orders", "/api/v1/parcels/estimate", "/api/v1/parcels/estimate-fee"}) {
      boolean estimate = path.contains("estimate");
      String[] fields = estimate ? new String[]{"pickupCity", "deliveryCity", "parcelCategory"}
          : new String[]{"sender.name", "recipient.name", "sender.address", "recipient.address", "sender.city", "recipient.city", "parcel.category", "parcel.description", "notes", "externalOrderRef"};
      for (String field : fields) {
        if (field.equals("externalOrderRef") && !path.contains("merchant")) continue;
        int max = field.endsWith("address") || field.endsWith("description") ? 255 : field.equals("notes") ? 500 : field.equals("parcel.category") ? 40 : 100;
        int min = field.endsWith("address") ? 8 : field.endsWith("name") || field.equals("parcel.category") || field.equals("externalOrderRef") ? 1 : 0;
        for (int length : new int[]{Math.max(0, min - 1), min, min + 1, max - 1, max, max + 1}) {
          ObjectNode request = estimate ? mapper.createObjectNode().put("weightKg", 1) : body();
          String[] parts = field.split("\\.");
          ObjectNode parent = parts.length == 1 ? request : (ObjectNode)request.get(parts[0]);
          parent.put(parts[parts.length - 1], "😀".repeat(length));
          int expected = length >= min && length <= max ? 200 : estimate ? 400 : 422;
          var response = mvc.perform(post(path).with(jwt().jwt(j -> j.subject("synthetic-124")).authorities(new SimpleGrantedAuthority(path.contains("merchant") ? "ROLE_MERCHANT" : "ROLE_CUSTOMER")))
              .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
              .andExpect(status().is(expected));
          if (expected != 200) response.andExpect(jsonPath("$.fieldErrors['" + field + "']").exists());
        }
      }
    }
  }

  @org.junit.jupiter.api.Test
  void missingAndNullRequiredFieldsAreReportedTogetherInStableOrder() throws Exception {
    for (String path : new String[]{"/api/v1/parcels", "/api/v1/merchant/orders"}) {
      for (String input : new String[]{"{}", "{\"sender\":null,\"recipient\":null,\"parcel\":null}"}) {
        String result = mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority(path.contains("merchant") ? "ROLE_MERCHANT" : "ROLE_CUSTOMER")))
            .contentType(MediaType.APPLICATION_JSON).content(input))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors.sender").exists())
            .andExpect(jsonPath("$.fieldErrors.recipient").exists()).andExpect(jsonPath("$.fieldErrors.parcel").exists())
            .andReturn().getResponse().getContentAsString();
        java.util.List<String> keys = new java.util.ArrayList<>();
        mapper.readTree(result).get("fieldErrors").fieldNames().forEachRemaining(keys::add);
        assertThat(keys).isSorted();
      }
    }
  }

  @org.junit.jupiter.api.Test
  void longAndWrongTypeContactFieldsFailWithoutCallingServices() throws Exception {
    for (String path : new String[]{"/api/v1/parcels", "/api/v1/merchant/orders"}) {
      for (String contact : new String[]{"sender", "recipient"}) {
        for (String field : new String[]{"name", "phone", "address", "city"}) {
          ObjectNode request = body();
          ((ObjectNode)request.get(contact)).put(field, "x".repeat(100000));
          String result = mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority(path.contains("merchant") ? "ROLE_MERCHANT" : "ROLE_CUSTOMER")))
              .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
              .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.fieldErrors['" + contact + "." + field + "']").exists())
              .andReturn().getResponse().getContentAsString();
          assertThat(result.length()).isLessThan(2000);
          ((ObjectNode)request.get(contact)).put(field, 123);
          mvc.perform(post(path).with(jwt().authorities(new SimpleGrantedAuthority(path.contains("merchant") ? "ROLE_MERCHANT" : "ROLE_CUSTOMER")))
              .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request))).andExpect(status().isBadRequest());
        }
      }
    }
    verifyNoInteractions(bookings, orders, pricing);
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
