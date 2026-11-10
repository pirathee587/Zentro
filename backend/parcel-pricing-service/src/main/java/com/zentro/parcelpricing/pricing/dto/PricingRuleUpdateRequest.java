package com.zentro.parcelpricing.pricing.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Body of PUT /api/v1/admin/pricing-rules.
 * Enforces boundary validation matching the authoritative business rules and Dispatcher UI controls:
 * - baseFee: required, > 0.00, <= 1,000,000.00, at most 2 decimal places.
 * - includedWeightKg: required, > 0.00, <= 100.00 kg, at most 2 decimal places.
 * - additionalWeightRatePerKg: required, >= 0.00, <= 1,000,000.00, at most 2 decimal places.
 * - distanceSurchargeOtherDistrict: required, >= 0.00, <= 1,000,000.00, at most 2 decimal places.
 * - expressSurcharge: required, >= 0.00, <= 1,000,000.00, at most 2 decimal places.
 * - perKmRate: required, >= 0.00, <= 100,000.00, at most 2 decimal places.
 * - currency: optional string (defaults to LKR); if provided must be a 3-letter ISO code.
 */
public record PricingRuleUpdateRequest(
    @NotNull(message = "Base fee is required.")
    @DecimalMin(value = "0.01", message = "Base fee must be greater than zero.")
    @DecimalMax(value = "1000000.00", message = "Base fee cannot exceed 1,000,000.00.")
    @Digits(integer = 7, fraction = 2, message = "Base fee must have at most 2 decimal places.")
    BigDecimal baseFee,

    @NotNull(message = "Included weight is required.")
    @DecimalMin(value = "0.01", message = "Included weight must be greater than zero.")
    @DecimalMax(value = "100.00", message = "Included weight cannot exceed 100.00 kg.")
    @Digits(integer = 3, fraction = 2, message = "Included weight must have at most 2 decimal places.")
    BigDecimal includedWeightKg,

    @NotNull(message = "Rate per additional kg is required.")
    @DecimalMin(value = "0.00", message = "Rate per additional kg cannot be negative.")
    @DecimalMax(value = "1000000.00", message = "Rate per additional kg cannot exceed 1,000,000.00.")
    @Digits(integer = 7, fraction = 2, message = "Rate per additional kg must have at most 2 decimal places.")
    BigDecimal additionalWeightRatePerKg,

    @NotNull(message = "Other-district surcharge is required.")
    @DecimalMin(value = "0.00", message = "Other-district surcharge cannot be negative.")
    @DecimalMax(value = "1000000.00", message = "Other-district surcharge cannot exceed 1,000,000.00.")
    @Digits(integer = 7, fraction = 2, message = "Other-district surcharge must have at most 2 decimal places.")
    BigDecimal distanceSurchargeOtherDistrict,

    @NotNull(message = "Express surcharge is required.")
    @DecimalMin(value = "0.00", message = "Express surcharge cannot be negative.")
    @DecimalMax(value = "1000000.00", message = "Express surcharge cannot exceed 1,000,000.00.")
    @Digits(integer = 7, fraction = 2, message = "Express surcharge must have at most 2 decimal places.")
    BigDecimal expressSurcharge,

    @NotNull(message = "Rate per km is required.")
    @DecimalMin(value = "0.00", message = "Rate per km cannot be negative.")
    @DecimalMax(value = "100000.00", message = "Rate per km cannot exceed 100,000.00.")
    @Digits(integer = 6, fraction = 2, message = "Rate per km must have at most 2 decimal places.")
    BigDecimal perKmRate,

    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter ISO code.")
    String currency
) {}
