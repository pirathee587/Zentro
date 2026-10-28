package com.zentro.parcelpricing.service;

import static org.junit.jupiter.api.Assertions.*;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.ParcelSize;
import com.zentro.parcelpricing.domain.PricingRule;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PricingCalculationEngineTest {
  private PricingCalculationEngine engine;
  private PricingRule defaultRule;

  @BeforeEach
  void setUp() {
    engine = new PricingCalculationEngine();
    defaultRule = PricingRule.defaultStandard();
  }

  @Nested
  @DisplayName("Normal Pricing Calculations")
  class NormalCalculations {
    @Test
    @DisplayName("Standard delivery for base weight (1.0 kg) within same city")
    void standardBaseDelivery() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Colombo", DestinationType.SAME_CITY, ParcelSize.SMALL,
          new BigDecimal("1.00"), "Documents", false, null, null, null, null,
          "ZONE_COLOMBO_METRO", "ZONE_COLOMBO_METRO", "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      assertEquals(new BigDecimal("550.00"), response.fee());
      assertEquals("LKR 550", response.formattedFee());
      assertEquals("LKR", response.currency());
      assertEquals(2, response.estimatedDays());
      assertEquals(new BigDecimal("550.00"), response.breakdown().baseFee());
      assertEquals(new BigDecimal("0.00"), response.breakdown().weightFee());
      assertNull(response.breakdown().distanceSurcharge());
      assertNull(response.breakdown().expressFee());
    }

    @Test
    @DisplayName("Additional weight pricing (2.0 kg): base 550 + 1 * 120 = 670")
    void additionalWeightPricing() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Colombo", DestinationType.SAME_CITY, null,
          new BigDecimal("2.00"), "General", false, null, null, null, null,
          null, null, "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      assertEquals(new BigDecimal("670.00"), response.fee());
      assertEquals("LKR 670", response.formattedFee());
      assertEquals(new BigDecimal("120.00"), response.breakdown().weightFee());
      assertEquals(2, response.estimatedDays());
    }

    @Test
    @DisplayName("Fractional weight pricing (2.5 kg): base 550 + 1.5 * 120 = 730")
    void fractionalWeightPricing() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Colombo", DestinationType.SAME_CITY, null,
          new BigDecimal("2.50"), "General", false, null, null, null, null,
          null, null, "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      assertEquals(new BigDecimal("730.00"), response.fee());
      assertEquals("LKR 730", response.formattedFee());
      assertEquals(new BigDecimal("180.00"), response.breakdown().weightFee());
    }

    @Test
    @DisplayName("Other district distance surcharge applies 402 LKR and 3 days ETA")
    void otherDistrictPricing() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Kandy", DestinationType.OTHER_DISTRICT, null,
          new BigDecimal("2.00"), "General", false, null, null, null, null,
          null, null, "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      // 550 base + 120 weight + 402 distance surcharge = 1072
      assertEquals(new BigDecimal("1072.00"), response.fee());
      assertEquals("LKR 1,072", response.formattedFee());
      assertEquals(new BigDecimal("402.00"), response.breakdown().distanceSurcharge());
      assertEquals(3, response.estimatedDays());
    }

    @Test
    @DisplayName("Cross-zone delivery applies 402 LKR distance surcharge")
    void crossZonePricing() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Kandy", null, null,
          new BigDecimal("1.00"), "General", false, null, null, null, null,
          "ZONE_COLOMBO_METRO", "ZONE_CENTRAL", "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      assertEquals(new BigDecimal("952.00"), response.fee());
      assertEquals(new BigDecimal("402.00"), response.breakdown().distanceSurcharge());
      assertEquals(3, response.estimatedDays());
    }

    @Test
    @DisplayName("Express surcharge applies 300 LKR and 1 day ETA")
    void expressDeliveryPricing() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Colombo", DestinationType.SAME_CITY, null,
          new BigDecimal("1.00"), "General", true, null, null, null, null,
          null, null, "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      assertEquals(new BigDecimal("850.00"), response.fee());
      assertEquals("LKR 850", response.formattedFee());
      assertEquals(new BigDecimal("300.00"), response.breakdown().expressFee());
      assertEquals(1, response.estimatedDays());
    }

    @Test
    @DisplayName("Full combination: 2.0 kg, other district, express delivery")
    void fullCombinationPricing() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Jaffna", DestinationType.OTHER_DISTRICT, null,
          new BigDecimal("2.00"), "Electronics", true, null, null, null, null,
          null, null, "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      // 550 base + 120 weight + 402 distance + 300 express = 1372
      assertEquals(new BigDecimal("1372.00"), response.fee());
      assertEquals("LKR 1,372", response.formattedFee());
      assertEquals(1, response.estimatedDays());
    }

    @Test
    @DisplayName("Volumetric weight pricing when dimensions exceed actual weight")
    void volumetricWeightPricing() {
      // 50 x 40 x 30 cm = 60,000 cm3 / 5000 = 12.0 kg volumetric weight
      // Actual weight = 2.0 kg. Chargeable weight = 12.0 kg.
      // Billable extra = 12 - 1 = 11 kg -> 11 * 120 = 1320
      // Total = 550 + 1320 = 1870 LKR
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Colombo", DestinationType.SAME_CITY, null,
          new BigDecimal("2.00"), "Box", false, null,
          new BigDecimal("50.00"), new BigDecimal("40.00"), new BigDecimal("30.00"),
          null, null, "LKR"
      );

      FeeEstimateResponse response = engine.calculate(request, defaultRule);

      assertEquals(new BigDecimal("1870.00"), response.fee());
      assertEquals(new BigDecimal("1320.00"), response.breakdown().weightFee());
    }

    @Test
    @DisplayName("Repeated calculations with identical inputs produce identical deterministic results")
    void deterministicCalculation() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          "Colombo", "Galle", DestinationType.OTHER_DISTRICT, null,
          new BigDecimal("3.75"), "Apparel", true, null, null, null, null,
          null, null, "LKR"
      );

      FeeEstimateResponse res1 = engine.calculate(request, defaultRule);
      FeeEstimateResponse res2 = engine.calculate(request, defaultRule);

      assertEquals(res1.fee(), res2.fee());
      assertEquals(res1.formattedFee(), res2.formattedFee());
      assertEquals(res1.estimatedDays(), res2.estimatedDays());
      assertEquals(res1.breakdown().baseFee(), res2.breakdown().baseFee());
      assertEquals(res1.breakdown().weightFee(), res2.breakdown().weightFee());
      assertEquals(res1.breakdown().distanceSurcharge(), res2.breakdown().distanceSurcharge());
      assertEquals(res1.breakdown().expressFee(), res2.breakdown().expressFee());
    }
  }

  @Nested
  @DisplayName("Boundary and Threshold Cases")
  class BoundaryCases {
    @Test
    @DisplayName("Weight exactly at threshold (1.0 kg) incurs zero extra weight fee")
    void weightExactlyAtThreshold() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null,
          new BigDecimal("1.00"), null, false, null, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, defaultRule);
      assertEquals(new BigDecimal("0.00"), res.breakdown().weightFee());
      assertEquals(new BigDecimal("550.00"), res.fee());
    }

    @Test
    @DisplayName("Weight immediately below threshold (0.99 kg) incurs zero extra weight fee")
    void weightImmediatelyBelowThreshold() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null,
          new BigDecimal("0.99"), null, false, null, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, defaultRule);
      assertEquals(new BigDecimal("0.00"), res.breakdown().weightFee());
      assertEquals(new BigDecimal("550.00"), res.fee());
    }

    @Test
    @DisplayName("Weight immediately above threshold (1.01 kg) incurs proportional extra weight fee")
    void weightImmediatelyAboveThreshold() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null,
          new BigDecimal("1.01"), null, false, null, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, defaultRule);
      // 0.01 * 120 = 1.20
      assertEquals(new BigDecimal("1.20"), res.breakdown().weightFee());
      assertEquals(new BigDecimal("551.20"), res.fee());
      assertEquals("LKR 551.20", res.formattedFee());
    }

    @Test
    @DisplayName("Minimum valid weight (0.01 kg) is permitted")
    void minimumValidWeight() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null,
          new BigDecimal("0.01"), null, false, null, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, defaultRule);
      assertEquals(new BigDecimal("550.00"), res.fee());
    }

    @Test
    @DisplayName("Maximum valid parcel weight (100.0 kg)")
    void maximumValidWeight() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null,
          new BigDecimal("100.00"), null, false, null, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, defaultRule);
      // 550 + 99 * 120 = 550 + 11880 = 12430.00
      assertEquals(new BigDecimal("12430.00"), res.fee());
      assertEquals("LKR 12,430", res.formattedFee());
    }

    @Test
    @DisplayName("Zero distance is permitted and produces zero distance fee")
    void zeroDistancePermitted() {
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null,
          new BigDecimal("1.00"), null, false, BigDecimal.ZERO, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, defaultRule);
      assertNull(res.breakdown().distanceSurcharge());
      assertEquals(new BigDecimal("550.00"), res.fee());
    }

    @ParameterizedTest
    @CsvSource({
        "SMALL, 550.00",
        "MEDIUM, 790.00",
        "LARGE, 1030.00"
    })
    @DisplayName("Fallback weight by parcelSize when weight is omitted")
    void fallbackByParcelSize(ParcelSize size, String expectedFee) {
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, size,
          null, null, false, null, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, defaultRule);
      assertEquals(new BigDecimal(expectedFee), res.fee());
    }

    @Test
    @DisplayName("Currency rounding uses HALF_UP for fractional cents")
    void currencyRoundingHalfUp() {
      // Test pricing rule with non-integer rates to verify HALF_UP rounding
      PricingRule customRule = new PricingRule(
          defaultRule.id(), "CUSTOM", new BigDecimal("550.00"), new BigDecimal("1.00"),
          new BigDecimal("125.555"), new BigDecimal("402.00"), new BigDecimal("300.00"),
          BigDecimal.ZERO, "LKR", true, null
      );
      // 1.5 kg -> 0.5 * 125.555 = 62.7775 -> rounds to 62.78
      FeeEstimateRequest request = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null,
          new BigDecimal("1.50"), null, false, null, null, null, null, null, null, "LKR"
      );
      FeeEstimateResponse res = engine.calculate(request, customRule);
      assertEquals(new BigDecimal("62.78"), res.breakdown().weightFee());
      assertEquals(new BigDecimal("612.78"), res.fee());
    }
  }

  @Nested
  @DisplayName("Invalid Inputs and Error Handling")
  class InvalidCases {
    @Test
    @DisplayName("Null request throws IllegalArgumentException")
    void nullRequestThrows() {
      assertThrows(IllegalArgumentException.class, () -> engine.calculate(null, defaultRule));
    }

    @Test
    @DisplayName("Null rule throws IllegalArgumentException")
    void nullRuleThrows() {
      FeeEstimateRequest req = new FeeEstimateRequest(null, null, null, null, BigDecimal.ONE, null, null, null, null, null, null, null, null, null);
      assertThrows(IllegalArgumentException.class, () -> engine.calculate(req, null));
    }

    @Test
    @DisplayName("Missing weight, dimensions, and parcel size throws IllegalArgumentException")
    void missingWeightThrows() {
      FeeEstimateRequest req = new FeeEstimateRequest(null, null, DestinationType.SAME_CITY, null, null, null, false, null, null, null, null, null, null, "LKR");
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.calculate(req, defaultRule));
      assertTrue(ex.getMessage().contains("Either weightKg, dimensions, or parcelSize must be provided"));
    }

    @Test
    @DisplayName("Negative weight throws IllegalArgumentException")
    void negativeWeightThrows() {
      FeeEstimateRequest req = new FeeEstimateRequest(null, null, DestinationType.SAME_CITY, null, new BigDecimal("-1.50"), null, false, null, null, null, null, null, null, "LKR");
      assertThrows(IllegalArgumentException.class, () -> engine.calculate(req, defaultRule));
    }

    @Test
    @DisplayName("Negative dimension throws IllegalArgumentException")
    void negativeDimensionsThrows() {
      FeeEstimateRequest req = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null, null, null, false, null,
          new BigDecimal("-10.00"), new BigDecimal("10.00"), new BigDecimal("10.00"),
          null, null, "LKR"
      );
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.calculate(req, defaultRule));
      assertTrue(ex.getMessage().contains("positive"));
    }

    @Test
    @DisplayName("Partial dimensions throw IllegalArgumentException")
    void partialDimensionsThrows() {
      FeeEstimateRequest req = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null, null, null, false, null,
          new BigDecimal("10.00"), new BigDecimal("10.00"), null,
          null, null, "LKR"
      );
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.calculate(req, defaultRule));
      assertTrue(ex.getMessage().contains("length, width, and height must all be provided"));
    }

    @Test
    @DisplayName("Unsupported currency throws IllegalArgumentException")
    void unsupportedCurrencyThrows() {
      FeeEstimateRequest req = new FeeEstimateRequest(
          null, null, DestinationType.SAME_CITY, null, BigDecimal.ONE, null, false, null, null, null, null, null, null, "USD"
      );
      IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> engine.calculate(req, defaultRule));
      assertTrue(ex.getMessage().contains("Unsupported currency"));
    }
  }
}
