package com.zentro.parcelpricing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.parcelpricing.controller.AdminPricingRuleController;
import com.zentro.parcelpricing.controller.ParcelPricingController;
import com.zentro.parcelpricing.controller.ZoneController;
import com.zentro.parcelpricing.domain.PricingRule;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.dto.PricingRuleUpdateRequest;
import com.zentro.parcelpricing.service.PricingService;
import com.zentro.parcelpricing.service.ZoneService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {
    ParcelPricingController.class,
    ZoneController.class,
    AdminPricingRuleController.class
})
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class ParcelPricingAuthorizationAndEndpointTests {
  @Autowired MockMvc mvc;
  @MockBean PricingService pricingService;
  @MockBean ZoneService zoneService;
  @MockBean JwtDecoder jwtDecoder;

  @Test
  @DisplayName("Fee estimate requires authentication")
  void feeEstimateRequiresAuthentication() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .contentType("application/json")
        .content("{\"weightKg\": 2.0}"))
        .andExpect(status().isUnauthorized());
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

  @Test
  @DisplayName("Invalid negative weight returns 400 Bad Request Problem Details")
  void negativeWeightReturnsBadRequest() throws Exception {
    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": -5.0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"));
  }

  @Test
  @DisplayName("Unsupported origin zone returns 400 Bad Request Problem Details")
  void unsupportedZoneReturnsBadRequest() throws Exception {
    when(zoneService.isValidZone("UNKNOWN_ZONE")).thenReturn(false);

    mvc.perform(post("/api/v1/parcels/estimate")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"weightKg\": 1.0, \"originZone\": \"UNKNOWN_ZONE\"}"))
        .andExpect(status().isBadRequest());
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
}
