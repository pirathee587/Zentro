package com.zentro.parcelpricing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

public record FeeEstimateResponse(
    BigDecimal fee,
    String formattedFee,
    String currency,
    int estimatedDays,
    Breakdown breakdown
) {
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Breakdown(
      BigDecimal baseFee,
      BigDecimal weightFee,
      BigDecimal distanceSurcharge,
      BigDecimal expressFee
  ) {}
}
