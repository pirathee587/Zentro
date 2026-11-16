package com.zentro.trackingreporting.graphql.dto;

import java.math.BigDecimal;

public record DashboardFeeEstimate(BigDecimal fee, String formattedFee, String currency, int estimatedDays,
    Breakdown breakdown) {
  public record Breakdown(BigDecimal baseFee, BigDecimal weightFee, BigDecimal distanceSurcharge, BigDecimal expressFee) { }
}
