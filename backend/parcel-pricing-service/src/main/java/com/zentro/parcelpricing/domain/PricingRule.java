package com.zentro.parcelpricing.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PricingRule(
    UUID id,
    String ruleName,
    BigDecimal baseFee,
    BigDecimal includedWeightKg,
    BigDecimal additionalWeightRatePerKg,
    BigDecimal distanceSurchargeOtherDistrict,
    BigDecimal expressSurcharge,
    BigDecimal perKmRate,
    String currency,
    boolean active,
    OffsetDateTime updatedAt
) {
  public static PricingRule defaultStandard() {
    return new PricingRule(
        UUID.fromString("00000000-0000-0000-0000-000000000001"),
        "STANDARD_DEFAULT",
        new BigDecimal("550.00"),
        new BigDecimal("1.00"),
        new BigDecimal("120.00"),
        new BigDecimal("402.00"),
        new BigDecimal("300.00"),
        new BigDecimal("0.00"),
        "LKR",
        true,
        OffsetDateTime.now()
    );
  }
}
