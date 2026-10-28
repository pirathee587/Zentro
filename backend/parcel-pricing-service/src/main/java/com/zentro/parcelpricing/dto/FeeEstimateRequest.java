package com.zentro.parcelpricing.dto;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.ParcelSize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record FeeEstimateRequest(
    String pickupCity,
    String deliveryCity,
    DestinationType destinationType,
    ParcelSize parcelSize,
    @DecimalMin(value = "0.01", message = "Weight must be greater than zero")
    @DecimalMax(value = "100.00", message = "Weight cannot exceed 100 kg")
    BigDecimal weightKg,
    String parcelCategory,
    Boolean isExpress,
    @DecimalMin(value = "0.00", message = "Distance cannot be negative")
    BigDecimal distanceKm,
    @DecimalMin(value = "0.01", message = "Length must be greater than zero")
    BigDecimal lengthCm,
    @DecimalMin(value = "0.01", message = "Width must be greater than zero")
    BigDecimal widthCm,
    @DecimalMin(value = "0.01", message = "Height must be greater than zero")
    BigDecimal heightCm,
    String originZone,
    String destinationZone,
    String currency
) {
  public boolean hasDimensions() {
    return lengthCm != null && widthCm != null && heightCm != null;
  }
}
