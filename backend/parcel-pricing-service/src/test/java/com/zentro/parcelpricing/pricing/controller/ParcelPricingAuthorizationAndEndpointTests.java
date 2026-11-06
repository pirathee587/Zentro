package com.zentro.parcelpricing.pricing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.pricing.rules.PricingRule;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.pricing.dto.PricingRuleUpdateRequest;
import com.zentro.parcelpricing.exception.GlobalPricingExceptionHandler;
import com.zentro.parcelpricing.exception.PricingUnavailableException;
import com.zentro.parcelpricing.parcel.repository.MerchantOrderRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import com.zentro.parcelpricing.parcel.repository.ParcelStatusRepository;
import com.zentro.parcelpricing.pricing.rules.PricingCalculationEngine;
import com.zentro.parcelpricing.pricing.service.PricingService;
import com.zentro.parcelpricing.pricing.service.ZoneService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@WebMvcTest(controllers = {
    ParcelPricingController.class,
    ZoneController.class,
    AdminPricingRuleController.class
})
@Import({
    com.zentro.security.config.ZentroSecurityAutoConfiguration.class,
    GlobalPricingExceptionHandler.class
})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class ParcelPricingAuthorizationAndEndpointTests {
  private static final PricingCalculationEngine ENGINE = new PricingCalculationEngine();
  private static final PricingRule DEFAULT_RULE = PricingRule.defaultStandard();

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @MockBean PricingService pricingService;
  @MockBean ZoneService zoneService;
  @MockBean ParcelBookingRepository bookingRepository;
  @MockBean MerchantOrderRepository merchantOrderRepository;
  @MockBean ParcelStatusRepository statusRepository;
  @MockBean JwtDecoder jwtDecoder;

  @Test
  @DisplayName("Fee estimate requires authentication (401 when token is missing)")
  void feeEstimateRequiresAuthentication() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .contentType("application/json")
        .content("{\"weightKg\": 2.0}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Invalid or expired JWT token returns 401 Unauthorized")
  void invalidOrExpiredTokenReturns401() throws Exception {
    when(jwtDecoder.decode("expired-or-invalid-token"))
        .thenThrow(new BadJwtException("Jwt expired"));

    mvc.perform(post("/api/v1/parcels/estimate")
        .header("Authorization", "Bearer expired-or-invalid-token")
        .contentType("application/json")
        .content("{\"weightKg\": 2.0}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Customer can calculate fee estimate")
  void customerCanCalculateFeeEstimate() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenReturn(new FeeEstimateResponse(
            new BigDecimal("670.00"), "LKR 670", "LKR", 2,
            new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), new BigDecimal("120.00"), null, null)
        ));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 2.0, \"destinationType\": \"SAME_CITY\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee").value(670.00))
        .andExpect(jsonPath("$.formattedFee").value("LKR 670"))
        .andExpect(jsonPath("$.currency").value("LKR"))
        .andExpect(jsonPath("$.estimatedDays").value(2));

    verify(pricingService).estimateFee(any(FeeEstimateRequest.class));
  }

  @Test
  @DisplayName("Dispatcher can calculate fee estimate")
  void dispatcherCanCalculateFeeEstimate() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenReturn(new FeeEstimateResponse(
            new BigDecimal("550.00"), "LKR 550", "LKR", 2,
            new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), BigDecimal.ZERO, null, null)
        ));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Courier cannot access pricing fee estimate endpoint")
  void courierCannotAccessEstimate() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Merchant cannot access customer parcel estimate endpoint directly")
  void merchantCannotAccessEstimate() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(pricingService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_COURIER", "ROLE_MERCHANT", "ROLE_SERVICE"})
  @DisplayName("Wrong roles receive 403 Forbidden Problem Details")
  void wrongRolesReturn403(String role) throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority(role)))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0}"))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Fee estimate alias endpoint /api/v1/parcels/estimate-fee also works")
  void feeEstimateAliasEndpointWorks() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenReturn(new FeeEstimateResponse(
            new BigDecimal("1072.00"), "LKR 1,072", "LKR", 3,
            new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), new BigDecimal("120.00"), new BigDecimal("402.00"), null)
        ));

    mvc.perform(post("/api/v1/parcels/estimate-fee")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 2.0, \"destinationType\": \"OTHER_DISTRICT\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee").value(1072.00));
  }

  @ParameterizedTest(name = "Size={0}, Origin={1}, Dest={2}, Express={3} -> Total={4}")
  @CsvSource({
      "SMALL,  ZONE_COLOMBO_METRO, ZONE_COLOMBO_METRO, false, 550.00,  550.00, 0.00,   ,       ,       2",
      "MEDIUM, ZONE_COLOMBO_METRO, ZONE_WESTERN,       false, 1192.00, 550.00, 240.00, 402.00, ,       3",
      "LARGE,  ZONE_CENTRAL,       ZONE_SOUTHERN,      false, 1432.00, 550.00, 480.00, 402.00, ,       3",
      "SMALL,  ZONE_SOUTHERN,      ZONE_NORTHERN,      true,  1252.00, 550.00, 0.00,   402.00, 300.00, 1",
      "MEDIUM, ZONE_NORTHERN,      ZONE_NORTHERN,      true,  1090.00, 550.00, 240.00, ,       300.00, 1",
      "LARGE,  ZONE_WESTERN,       ZONE_WESTERN,       false, 1030.00, 550.00, 480.00, ,       ,       2"
  })
  @DisplayName("Valid quotes for each size and zone match pricing rules and calculation engine exactly")
  void validQuotesForEachSizeAndZoneMatchCalculationEngine(
      ParcelSize size,
      String originZone,
      String destinationZone,
      boolean isExpress,
      String expectedTotal,
      String expectedBase,
      String expectedWeight,
      String expectedDistance,
      String expectedExpress,
      int expectedDays) throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenAnswer(inv -> ENGINE.calculate(inv.getArgument(0), DEFAULT_RULE));

    FeeEstimateRequest request = new FeeEstimateRequest(
        "Origin", "Destination", null, size, null, "General", isExpress,
        null, null, null, null, originZone, destinationZone, "LKR");
    FeeEstimateResponse expectedFromEngine = ENGINE.calculate(request, DEFAULT_RULE);

    MvcResult result = mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("LKR"))
        .andExpect(jsonPath("$.estimatedDays").value(expectedDays))
        .andReturn();

    FeeEstimateResponse actual = objectMapper.readValue(
        result.getResponse().getContentAsString(), FeeEstimateResponse.class);

    assertThat(actual.fee()).isEqualByComparingTo(new BigDecimal(expectedTotal));
    assertThat(actual.breakdown().baseFee()).isEqualByComparingTo(new BigDecimal(expectedBase));
    assertThat(actual.breakdown().weightFee()).isEqualByComparingTo(new BigDecimal(expectedWeight));
    if (expectedDistance != null) {
      assertThat(actual.breakdown().distanceSurcharge()).isEqualByComparingTo(new BigDecimal(expectedDistance));
    } else {
      assertThat(actual.breakdown().distanceSurcharge()).isNull();
    }
    if (expectedExpress != null) {
      assertThat(actual.breakdown().expressFee()).isEqualByComparingTo(new BigDecimal(expectedExpress));
    } else {
      assertThat(actual.breakdown().expressFee()).isNull();
    }
    // Consistency check with PricingCalculationEngine
    assertThat(actual).isEqualTo(expectedFromEngine);
  }

  @ParameterizedTest(name = "Boundary valid weight {0} kg -> fee {1}")
  @CsvSource({
      "0.01,   550.00",
      "1.00,   550.00",
      "1.01,   551.20",
      "100.00, 12430.00"
  })
  @DisplayName("Boundary valid weights succeed and return exact calculated fees")
  void boundaryValidWeightsSucceed(String weight, String expectedFee) throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenAnswer(inv -> ENGINE.calculate(inv.getArgument(0), DEFAULT_RULE));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": " + weight + ", \"destinationType\": \"SAME_CITY\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee").value(new BigDecimal(expectedFee).doubleValue()));
  }

  @Test
  @DisplayName("Invalid negative weight returns 400 Bad Request Problem Details with fieldErrors")
  void negativeWeightReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": -5.0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.fieldErrors.weightKg").exists())
        .andExpect(jsonPath("$.invalidFields.weightKg").exists());
  }

  @ParameterizedTest(name = "Invalid payload {0} -> 400 on field {1}")
  @CsvSource(delimiter = '|', value = {
      "{}|weightKg",
      "{\"weightKg\": 0}|weightKg",
      "{\"weightKg\": -0.01}|weightKg",
      "{\"weightKg\": 100.01}|weightKg",
      "{\"weightKg\": 999999}|weightKg",
      "{\"weightKg\": \"not-a-number\"}|weightKg",
      "{\"parcelSize\": \"HUGE\"}|parcelSize",
      "{\"weightKg\": 1.0, \"destinationType\": \"INTERNATIONAL\"}|destinationType",
      "{\"weightKg\": 1.0, \"distanceKm\": -0.01}|distanceKm",
      "{\"weightKg\": 1.0, \"distanceKm\": 2000.01}|distanceKm",
      "{\"lengthCm\": 0, \"widthCm\": 10, \"heightCm\": 10}|lengthCm",
      "{\"lengthCm\": 500.01, \"widthCm\": 10, \"heightCm\": 10}|lengthCm",
      "{\"lengthCm\": 10, \"widthCm\": -1, \"heightCm\": 10}|widthCm",
      "{\"lengthCm\": 10, \"widthCm\": 10, \"heightCm\": 0}|heightCm",
      "{\"lengthCm\": 10, \"widthCm\": 10}|heightCm",
      "{\"lengthCm\": 100, \"widthCm\": 100, \"heightCm\": 100}|weightKg",
      "{\"weightKg\": 1.0, \"originZone\": \"   \"}|originZone",
      "{\"weightKg\": 1.0, \"destinationZone\": \"   \"}|destinationZone",
      "{\"weightKg\": 1.0, \"currency\": \"   \"}|currency"
  })
  @DisplayName("Each missing or invalid field returns 400 with a field-level error message and no stack trace")
  void invalidInputsReturnFieldLevelError(String payload, String expectedField) throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.fieldErrors." + expectedField).exists())
        .andExpect(content().string(not(containsString("Exception"))));

    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Malformed JSON returns 400 Bad Request Problem Details without stack trace")
  void malformedJsonReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": "))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(content().string(not(containsString("Jackson"))));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Unsupported origin zone returns 400 Bad Request Problem Details with fieldErrors.originZone")
  void unsupportedZoneReturnsBadRequest() throws Exception {
    when(zoneService.isValidZone("UNKNOWN_ZONE")).thenReturn(false);

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0, \"originZone\": \"UNKNOWN_ZONE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.originZone").exists());
  }

  @Test
  @DisplayName("Unsupported destination zone returns 400 Bad Request Problem Details with fieldErrors.destinationZone")
  void unsupportedDestinationZoneReturnsBadRequest() throws Exception {
    when(zoneService.isValidZone("ZONE_COLOMBO_METRO")).thenReturn(true);
    when(zoneService.isValidZone("UNKNOWN_DEST")).thenReturn(false);

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0, \"originZone\": \"ZONE_COLOMBO_METRO\", \"destinationZone\": \"UNKNOWN_DEST\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.destinationZone").exists());
  }

  @Test
  @DisplayName("Unsupported route/zone combination (SAME_CITY across different zones) returns 400 with fieldErrors")
  void unsupportedRouteZoneCombinationReturnsBadRequest() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("""
            {
              "weightKg": 1.0,
              "destinationType": "SAME_CITY",
              "originZone": "ZONE_COLOMBO_METRO",
              "destinationZone": "ZONE_CENTRAL"
            }
            """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.destinationType").exists());
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Unsupported currency returns 400 with fieldErrors.currency")
  void unsupportedCurrencyReturnsBadRequest() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenAnswer(inv -> ENGINE.calculate(inv.getArgument(0), DEFAULT_RULE));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0, \"currency\": \"USD\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.currency").exists());
  }

  @Test
  @DisplayName("Pricing data unavailable returns 503 Service Unavailable, never a zero or partial price")
  void pricingDataUnavailableReturns503() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenThrow(new PricingUnavailableException("Pricing service is temporarily unavailable.", new QueryTimeoutException("DB timeout")));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 2.0, \"destinationType\": \"SAME_CITY\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Service Unavailable"))
        .andExpect(jsonPath("$.fee").doesNotExist());
  }

  @Test
  @DisplayName("Zero or partial quote from pricing service is rejected with 503 Service Unavailable")
  void zeroOrPartialQuoteReturns503() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenReturn(new FeeEstimateResponse(BigDecimal.ZERO, "LKR 0", "LKR", 2, null));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 2.0, \"destinationType\": \"SAME_CITY\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Service Unavailable"))
        .andExpect(jsonPath("$.fee").doesNotExist());
  }

  @Test
  @DisplayName("Zone configuration failure returns 503 Service Unavailable")
  void zoneDataUnavailableReturns503() throws Exception {
    when(zoneService.isValidZone(any())).thenThrow(new QueryTimeoutException("Zone table unavailable"));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 2.0, \"originZone\": \"ZONE_COLOMBO_METRO\", \"destinationZone\": \"ZONE_CENTRAL\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.title").value("Service Unavailable"));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Quote is repeatable and creates no booking or other side effects")
  void quoteIsRepeatableAndSideEffectFree() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenAnswer(inv -> ENGINE.calculate(inv.getArgument(0), DEFAULT_RULE));

    String payload = "{\"weightKg\": 3.5, \"destinationType\": \"OTHER_DISTRICT\", \"isExpress\": true}";

    String firstResponse = mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content(payload))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    String secondResponse = mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content(payload))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();

    assertThat(firstResponse).isEqualTo(secondResponse);
    verify(pricingService, times(2)).estimateFee(any(FeeEstimateRequest.class));
    verifyNoInteractions(bookingRepository, merchantOrderRepository, statusRepository);
  }

  @Test
  @DisplayName("Zones endpoint requires authentication")
  void zonesRequireAuthentication() throws Exception {
    mvc.perform(get("/api/v1/zones")).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Authenticated users can read delivery zones")
  void authenticatedUserCanReadZones() throws Exception {
    when(zoneService.getActiveZones()).thenReturn(ZoneService.defaultZones());

    mvc.perform(get("/api/v1/zones")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("ZONE_COLOMBO_METRO"));
  }

  @Test
  @DisplayName("Customer cannot access admin pricing rules")
  void customerCannotAccessAdminPricingRules() throws Exception {
    mvc.perform(get("/api/v1/admin/pricing-rules")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("Dispatcher can read and update admin pricing rules")
  void dispatcherCanReadAndUpdatePricingRules() throws Exception {
    when(pricingService.getActiveRule()).thenReturn(PricingRule.defaultStandard());
    when(pricingService.updateStandardRule(any(PricingRuleUpdateRequest.class)))
        .thenReturn(PricingRule.defaultStandard());

    mvc.perform(get("/api/v1/admin/pricing-rules")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleName").value("STANDARD_DEFAULT"))
        .andExpect(jsonPath("$.baseFee").value(550.00));

    mvc.perform(put("/api/v1/admin/pricing-rules")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
        .contentType("application/json")
        .content("""
            {
              "baseFee": 580.00,
              "includedWeightKg": 1.00,
              "additionalWeightRatePerKg": 130.00,
              "distanceSurchargeOtherDistrict": 420.00,
              "expressSurcharge": 320.00,
              "perKmRate": 15.00,
              "currency": "LKR"
            }
            """))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("Destination zone provided without origin zone returns 400 Bad Request with fieldErrors.originZone")
  void destinationZoneWithoutOriginZoneReturnsBadRequest() throws Exception {
    when(zoneService.isValidZone("ZONE_COLOMBO_METRO")).thenReturn(true);

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0, \"destinationZone\": \"ZONE_COLOMBO_METRO\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.originZone").value("Origin zone is required when destination zone is provided."));

    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Origin zone provided without destination zone returns 400 Bad Request with fieldErrors.destinationZone")
  void originZoneWithoutDestinationZoneReturnsBadRequest() throws Exception {
    when(zoneService.isValidZone("ZONE_COLOMBO_METRO")).thenReturn(true);

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0, \"originZone\": \"ZONE_COLOMBO_METRO\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.destinationZone").value("Destination zone is required when origin zone is provided."));

    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("Case-insensitive currency parameter is accepted via API and normalized to rule currency")
  void caseInsensitiveCurrencyAcceptedViaApi() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenAnswer(inv -> ENGINE.calculate(inv.getArgument(0), DEFAULT_RULE));

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0, \"currency\": \"lkr\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.currency").value("LKR"))
        .andExpect(jsonPath("$.fee").value(550.00));
  }

  @Test
  @DisplayName("Volumetric weight exactly at 100 kg ceiling is permitted and succeeds")
  void volumetricWeightExactlyAtCeiling100KgAllowed() throws Exception {
    when(zoneService.isValidZone(any())).thenReturn(true);
    when(pricingService.estimateFee(any(FeeEstimateRequest.class)))
        .thenAnswer(inv -> ENGINE.calculate(inv.getArgument(0), DEFAULT_RULE));

    // 500 cm x 100 cm x 10 cm = 500,000 / 5,000 = 100.00 kg volumetric weight
    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"lengthCm\": 500.00, \"widthCm\": 100.00, \"heightCm\": 10.00}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fee").value(12430.00));
  }

  @Test
  @DisplayName("Fee estimate alias endpoint with invalid input returns 400 Bad Request Problem Details")
  void feeEstimateAliasEndpointWithInvalidInputReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate-fee")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": -5.0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.weightKg").exists());

    verifyNoInteractions(pricingService);
  }
}
