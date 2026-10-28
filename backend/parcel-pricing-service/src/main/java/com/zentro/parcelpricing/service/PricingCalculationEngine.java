package com.zentro.parcelpricing.service;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.PricingRule;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Deterministic and reusable calculation engine for Zentro parcel pricing.
 * Conforms to authoritative business rules:
 * - Chargeable weight: max(actualWeight, volumetricWeight(L*W*H/5000))
 * - Base fee: LKR 550.00 for up to 1.0 kg
 * - Additional weight: LKR 120.00 per extra chargeable kg above 1.0 kg
 * - Flat cross-zone/other-district surcharge: LKR 402.00
 * - Express surcharge: LKR 300.00
 * - Estimated delivery: 1 day (express), 3 days (cross-zone/other-district), 2 days (same-city)
 */
@Component
public class PricingCalculationEngine {
  private static final BigDecimal VOLUMETRIC_DIVISOR = new BigDecimal("5000");

  public FeeEstimateResponse calculate(FeeEstimateRequest request, PricingRule rule) {
    if (request == null) {
      throw new IllegalArgumentException("Fee estimate request cannot be null.");
    }
    if (rule == null) {
      throw new IllegalArgumentException("Pricing rule configuration cannot be null.");
    }

    // Currency check
    if (request.currency() != null && !request.currency().isBlank()
        && !request.currency().equalsIgnoreCase(rule.currency())) {
      throw new IllegalArgumentException("Unsupported currency '" + request.currency() + "'. Expected '" + rule.currency() + "'.");
    }

    // Determine chargeable weight
    BigDecimal actualWeight = request.weightKg();
    BigDecimal volumetricWeight = calculateVolumetricWeight(request);

    BigDecimal chargeableWeight;
    if (actualWeight != null && volumetricWeight != null) {
      chargeableWeight = actualWeight.max(volumetricWeight);
    } else if (actualWeight != null) {
      chargeableWeight = actualWeight;
    } else if (volumetricWeight != null) {
      chargeableWeight = volumetricWeight;
    } else if (request.parcelSize() != null) {
      chargeableWeight = request.parcelSize().defaultWeightKg();
    } else {
      throw new IllegalArgumentException("Either weightKg, dimensions, or parcelSize must be provided.");
    }

    if (chargeableWeight.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("Chargeable weight must be greater than zero.");
    }

    // 1. Base fee
    BigDecimal baseFee = rule.baseFee().setScale(2, RoundingMode.HALF_UP);

    // 2. Additional weight fee
    BigDecimal billableExtraWeight = chargeableWeight.subtract(rule.includedWeightKg()).max(BigDecimal.ZERO);
    BigDecimal weightFee = billableExtraWeight.multiply(rule.additionalWeightRatePerKg()).setScale(2, RoundingMode.HALF_UP);

    // 3. Distance / zone surcharge
    boolean isCrossZone = isCrossZoneOrOtherDistrict(request);
    BigDecimal distanceSurcharge;
    if (isCrossZone) {
      distanceSurcharge = rule.distanceSurchargeOtherDistrict().setScale(2, RoundingMode.HALF_UP);
    } else if (request.distanceKm() != null && request.distanceKm().compareTo(BigDecimal.ZERO) > 0
        && rule.perKmRate().compareTo(BigDecimal.ZERO) > 0) {
      distanceSurcharge = request.distanceKm().multiply(rule.perKmRate()).setScale(2, RoundingMode.HALF_UP);
    } else {
      distanceSurcharge = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    // 4. Express fee
    BigDecimal expressFee;
    if (Boolean.TRUE.equals(request.isExpress())) {
      expressFee = rule.expressSurcharge().setScale(2, RoundingMode.HALF_UP);
    } else {
      expressFee = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    // Total fee
    BigDecimal totalFee = baseFee.add(weightFee).add(distanceSurcharge).add(expressFee).setScale(2, RoundingMode.HALF_UP);

    // Delivery days estimation
    int estimatedDays;
    if (Boolean.TRUE.equals(request.isExpress())) {
      estimatedDays = 1;
    } else if (isCrossZone) {
      estimatedDays = 3;
    } else {
      estimatedDays = 2;
    }

    String formattedFee = formatCurrency(totalFee, rule.currency());

    FeeEstimateResponse.Breakdown breakdown = new FeeEstimateResponse.Breakdown(
        baseFee,
        weightFee,
        distanceSurcharge.compareTo(BigDecimal.ZERO) > 0 ? distanceSurcharge : null,
        expressFee.compareTo(BigDecimal.ZERO) > 0 ? expressFee : null
    );

    return new FeeEstimateResponse(totalFee, formattedFee, rule.currency(), estimatedDays, breakdown);
  }

  public BigDecimal calculateVolumetricWeight(FeeEstimateRequest request) {
    boolean hasAnyDimension = request.lengthCm() != null || request.widthCm() != null || request.heightCm() != null;
    if (hasAnyDimension) {
      if (!request.hasDimensions()) {
        throw new IllegalArgumentException("When specifying dimensions, length, width, and height must all be provided.");
      }
      if (request.lengthCm().compareTo(BigDecimal.ZERO) <= 0
          || request.widthCm().compareTo(BigDecimal.ZERO) <= 0
          || request.heightCm().compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("Parcel dimensions must be positive values.");
      }
      return request.lengthCm()
          .multiply(request.widthCm())
          .multiply(request.heightCm())
          .divide(VOLUMETRIC_DIVISOR, 4, RoundingMode.HALF_UP);
    }
    return null;
  }

  private boolean isCrossZoneOrOtherDistrict(FeeEstimateRequest request) {
    if (request.destinationType() == DestinationType.OTHER_DISTRICT) {
      return true;
    }
    if (request.originZone() != null && request.destinationZone() != null
        && !request.originZone().trim().isEmpty() && !request.destinationZone().trim().isEmpty()) {
      return !request.originZone().trim().equalsIgnoreCase(request.destinationZone().trim());
    }
    return false;
  }

  private String formatCurrency(BigDecimal amount, String currency) {
    if (amount.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
      return String.format(Locale.US, "%s %,d", currency, amount.longValue());
    }
    return String.format(Locale.US, "%s %,.2f", currency, amount);
  }
}
