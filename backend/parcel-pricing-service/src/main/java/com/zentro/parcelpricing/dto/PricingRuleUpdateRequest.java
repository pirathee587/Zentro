package com.zentro.parcelpricing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record PricingRuleUpdateRequest(
    @NotNull @DecimalMin("0.00") BigDecimal baseFee,
    @NotNull @DecimalMin("0.00") BigDecimal includedWeightKg,
    @NotNull @DecimalMin("0.00") BigDecimal additionalWeightRatePerKg,
    @NotNull @DecimalMin("0.00") BigDecimal distanceSurchargeOtherDistrict,
    @NotNull @DecimalMin("0.00") BigDecimal expressSurcharge,
    @NotNull @DecimalMin("0.00") BigDecimal perKmRate,
    String currency
) {}
