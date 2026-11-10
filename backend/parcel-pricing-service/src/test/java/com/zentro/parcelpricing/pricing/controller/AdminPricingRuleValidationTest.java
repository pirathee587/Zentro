package com.zentro.parcelpricing.pricing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.exception.GlobalPricingExceptionHandler;
import com.zentro.parcelpricing.pricing.dto.PricingRuleUpdateRequest;
import com.zentro.parcelpricing.pricing.rules.PricingRule;
import com.zentro.parcelpricing.pricing.service.PricingService;
import com.zentro.parcelpricing.pricing.service.ZoneService;
import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

@WebMvcTest(controllers = {AdminPricingRuleController.class, ZoneController.class})
@Import({ZentroSecurityAutoConfiguration.class, GlobalPricingExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class AdminPricingRuleValidationTest {

  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private PricingService pricingService;
  @MockBean private ZoneService zoneService;
  @MockBean private JwtDecoder jwtDecoder;

  private static final String ADMIN_PRICING_RULES_URL = "/api/v1/admin/pricing-rules";

  private String validPayload() {
    return """
        {
          "baseFee": 550.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "LKR"
        }
        """;
  }

  @Test
  @DisplayName("GET admin pricing rules requires DISPATCHER role")
  void getPricingRulesRequiresDispatcher() throws Exception {
    when(pricingService.getActiveRule()).thenReturn(PricingRule.defaultStandard());

    mvc.perform(get(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleName").value("STANDARD_DEFAULT"))
        .andExpect(jsonPath("$.baseFee").value(550.00))
        .andExpect(jsonPath("$.currency").value("LKR"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER"})
  @DisplayName("GET admin pricing rules rejects unauthorized roles with 403 Forbidden")
  void getPricingRulesRejectsUnauthorizedRoles(String role) throws Exception {
    mvc.perform(get(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority(role))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("GET admin pricing rules without authentication returns 401 Unauthorized")
  void getPricingRulesRequiresAuth() throws Exception {
    mvc.perform(get(ADMIN_PRICING_RULES_URL))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT admin pricing rules with valid payload updates rule and returns 200 OK")
  void putPricingRulesSucceedsWithValidPayload() throws Exception {
    PricingRule updated = new PricingRule(
        UUID.randomUUID(), "STANDARD_DEFAULT",
        new BigDecimal("600.00"), new BigDecimal("1.50"),
        new BigDecimal("130.00"), new BigDecimal("450.00"),
        new BigDecimal("350.00"), new BigDecimal("20.00"),
        "LKR", true, OffsetDateTime.now()
    );
    when(pricingService.updateStandardRule(any())).thenReturn(updated);

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(validPayload()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseFee").value(600.00))
        .andExpect(jsonPath("$.includedWeightKg").value(1.50))
        .andExpect(jsonPath("$.additionalWeightRatePerKg").value(130.00))
        .andExpect(jsonPath("$.currency").value("LKR"));
  }

  @Test
  @DisplayName("PUT rejects missing required fields with 400 Bad Request and field errors")
  void putRejectsMissingRequiredFields() throws Exception {
    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.baseFee").exists())
        .andExpect(jsonPath("$.fieldErrors.includedWeightKg").exists())
        .andExpect(jsonPath("$.fieldErrors.additionalWeightRatePerKg").exists())
        .andExpect(jsonPath("$.fieldErrors.distanceSurchargeOtherDistrict").exists())
        .andExpect(jsonPath("$.fieldErrors.expressSurcharge").exists())
        .andExpect(jsonPath("$.fieldErrors.perKmRate").exists())
        .andExpect(jsonPath("$.invalidFields.baseFee").exists());
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects zero baseFee (0.00) because base fee must be positive")
  void putRejectsZeroBaseFee() throws Exception {
    String payload = """
        {
          "baseFee": 0.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.baseFee").value("Base fee must be greater than zero."))
        .andExpect(jsonPath("$.invalidFields.baseFee").value("Base fee must be greater than zero."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects negative baseFee with 400 Bad Request")
  void putRejectsNegativeBaseFee() throws Exception {
    String payload = """
        {
          "baseFee": -50.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.baseFee").value("Base fee must be greater than zero."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects baseFee exceeding upper bound of 1,000,000.00")
  void putRejectsBaseFeeExceedingLimit() throws Exception {
    String payload = """
        {
          "baseFee": 1500000.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.baseFee").value("Base fee cannot exceed 1,000,000.00."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects baseFee with more than 2 decimal places")
  void putRejectsBaseFeeWithMoreThanTwoDecimals() throws Exception {
    String payload = """
        {
          "baseFee": 550.125,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.baseFee").value("Base fee must have at most 2 decimal places."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects zero includedWeightKg (0.00) because included weight must be positive")
  void putRejectsZeroIncludedWeightKg() throws Exception {
    String payload = """
        {
          "baseFee": 550.00,
          "includedWeightKg": 0.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.includedWeightKg").value("Included weight must be greater than zero."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects includedWeightKg exceeding 100 kg")
  void putRejectsIncludedWeightKgExceedingLimit() throws Exception {
    String payload = """
        {
          "baseFee": 550.00,
          "includedWeightKg": 105.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.includedWeightKg").value("Included weight cannot exceed 100.00 kg."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects negative rates and surcharges")
  void putRejectsNegativeRatesAndSurcharges() throws Exception {
    String payload = """
        {
          "baseFee": 550.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": -10.00,
          "distanceSurchargeOtherDistrict": -20.00,
          "expressSurcharge": -30.00,
          "perKmRate": -5.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.additionalWeightRatePerKg").value("Rate per additional kg cannot be negative."))
        .andExpect(jsonPath("$.fieldErrors.distanceSurchargeOtherDistrict").value("Other-district surcharge cannot be negative."))
        .andExpect(jsonPath("$.fieldErrors.expressSurcharge").value("Express surcharge cannot be negative."))
        .andExpect(jsonPath("$.fieldErrors.perKmRate").value("Rate per km cannot be negative."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects rates exceeding maximum bounds")
  void putRejectsRatesExceedingMaximumBounds() throws Exception {
    String payload = """
        {
          "baseFee": 550.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 1500000.00,
          "distanceSurchargeOtherDistrict": 1500000.00,
          "expressSurcharge": 1500000.00,
          "perKmRate": 150000.00,
          "currency": "LKR"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.additionalWeightRatePerKg").value("Rate per additional kg cannot exceed 1,000,000.00."))
        .andExpect(jsonPath("$.fieldErrors.distanceSurchargeOtherDistrict").value("Other-district surcharge cannot exceed 1,000,000.00."))
        .andExpect(jsonPath("$.fieldErrors.expressSurcharge").value("Express surcharge cannot exceed 1,000,000.00."))
        .andExpect(jsonPath("$.fieldErrors.perKmRate").value("Rate per km cannot exceed 100,000.00."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects currency that is not a 3-letter ISO code")
  void putRejectsInvalidCurrencyFormat() throws Exception {
    String payload = """
        {
          "baseFee": 550.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "TOOLONG"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.currency").value("Currency must be a 3-letter ISO code."));
    verifyNoInteractions(pricingService);
  }

  @Test
  @DisplayName("PUT rejects unsupported currency code (e.g. USD) with domain error")
  void putRejectsUnsupportedCurrency() throws Exception {
    when(pricingService.updateStandardRule(any()))
        .thenThrow(new com.zentro.parcelpricing.exception.PricingValidationException(
            "Unsupported currency 'USD'. Only 'LKR' is supported.",
            java.util.Map.of("currency", "Unsupported currency 'USD'. Only 'LKR' is supported.")));

    String payload = """
        {
          "baseFee": 550.00,
          "includedWeightKg": 1.00,
          "additionalWeightRatePerKg": 120.00,
          "distanceSurchargeOtherDistrict": 402.00,
          "expressSurcharge": 300.00,
          "perKmRate": 0.00,
          "currency": "USD"
        }
        """;

    mvc.perform(put(ADMIN_PRICING_RULES_URL)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.currency").value("Unsupported currency 'USD'. Only 'LKR' is supported."))
        .andExpect(jsonPath("$.invalidFields.currency").value("Unsupported currency 'USD'. Only 'LKR' is supported."));
  }

  @Test
  @DisplayName("GET /api/v1/zones/{code} returns 200 with delivery zone when zone exists")
  void getZoneByCodeReturnsZoneWhenFound() throws Exception {
    when(zoneService.getZoneByCode("ZONE_COLOMBO_METRO"))
        .thenReturn(java.util.Optional.of(new com.zentro.parcelpricing.pricing.rules.DeliveryZone(
            UUID.randomUUID(), "ZONE_COLOMBO_METRO", "Colombo Metro", "Colombo District", true)));

    mvc.perform(get("/api/v1/zones/ZONE_COLOMBO_METRO")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("ZONE_COLOMBO_METRO"))
        .andExpect(jsonPath("$.name").value("Colombo Metro"));
  }

  @Test
  @DisplayName("GET /api/v1/zones/{code} returns 404 when zone is not found")
  void getZoneByCodeReturns404WhenNotFound() throws Exception {
    when(zoneService.getZoneByCode("ZONE_NONEXISTENT"))
        .thenReturn(java.util.Optional.empty());

    mvc.perform(get("/api/v1/zones/ZONE_NONEXISTENT")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isNotFound());
  }
}

