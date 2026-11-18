package com.zentro.trackingreporting.graphql.service;

import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeRequest;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeResponse;
import com.zentro.trackingreporting.graphql.dto.DashboardFeeEstimate;
import java.math.BigDecimal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Read-only quotation over gRPC; S2 remains the pricing authority. */
@Component
public class DashboardPricingClient {
  private final ParcelPricingGrpcClient grpcClient;

  public DashboardPricingClient(ParcelPricingGrpcClient grpcClient) {
    this.grpcClient = grpcClient;
  }

  public DashboardFeeEstimate estimate(Jwt jwt, String pickupCity, String deliveryCity,
      double weightKg, String destinationType, String parcelCategory) {
    if ((pickupCity != null && (pickupCity.isBlank() || pickupCity.length() > 100))
        || (deliveryCity != null && (deliveryCity.isBlank() || deliveryCity.length() > 100))
        || !Double.isFinite(weightKg) || weightKg <= 0 || weightKg > 50
        || !("SAME_CITY".equals(destinationType) || "OTHER_DISTRICT".equals(destinationType))
        || (parcelCategory != null && parcelCategory.length() > 100)) {
      throw new IllegalArgumentException("Invalid quotation parameters");
    }

    EstimateDeliveryFeeRequest.Builder req = EstimateDeliveryFeeRequest.newBuilder()
        .setWeightKg(BigDecimal.valueOf(weightKg).toPlainString())
        .setDestinationType(destinationType);
    if (pickupCity != null) req.setPickupCity(pickupCity);
    if (deliveryCity != null) req.setDeliveryCity(deliveryCity);
    if (parcelCategory != null) req.setParcelCategory(parcelCategory);

    try {
      EstimateDeliveryFeeResponse result = grpcClient.estimateFee(req.build());
      if (result == null || result.getFee().isBlank()) {
        throw new IllegalArgumentException("Pricing response unavailable");
      }
      DashboardFeeEstimate.Breakdown breakdown = result.hasBreakdown()
          ? new DashboardFeeEstimate.Breakdown(
              decimalOrNull(result.getBreakdown().getBaseFee()),
              decimalOrNull(result.getBreakdown().getWeightFee()),
              decimalOrNull(result.getBreakdown().getDistanceSurcharge()),
              decimalOrNull(result.getBreakdown().getExpressFee()))
          : null;
      return new DashboardFeeEstimate(
          new BigDecimal(result.getFee()),
          result.getFormattedFee(),
          result.getCurrency(),
          result.getEstimatedDays(),
          breakdown);
    } catch (RuntimeException ex) {
      // Never expose/log provider bodies, tokens or internal addresses.
      throw new IllegalArgumentException("Delivery pricing is temporarily unavailable. Please retry.");
    }
  }

  private static BigDecimal decimalOrNull(String value) {
    return value == null || value.isBlank() ? null : new BigDecimal(value);
  }
}
