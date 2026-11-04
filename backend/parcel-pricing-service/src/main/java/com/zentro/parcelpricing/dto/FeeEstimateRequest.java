package com.zentro.parcelpricing.dto;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.ParcelSize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record FeeEstimateRequest(
    @Size(max = 100, message = "Pickup city must be at most 100 characters")
    String pickupCity,
    @Size(max = 100, message = "Delivery city must be at most 100 characters")
    String deliveryCity,
    DestinationType destinationType,
    ParcelSize parcelSize,
    @DecimalMin(value = "0.01", message = "Weight must be greater than zero")
    @DecimalMax(value = "100.00", message = "Weight cannot exceed 100 kg")
    BigDecimal weightKg,
    @Size(max = 100, message = "Parcel category must be at most 100 characters")
    String parcelCategory,
    Boolean isExpress,
    @DecimalMin(value = "0.00", message = "Distance cannot be negative")
    @DecimalMax(value = "2000.00", message = "Distance cannot exceed 2000 km")
    BigDecimal distanceKm,
    @DecimalMin(value = "0.01", message = "Length must be greater than zero")
    @DecimalMax(value = "500.00", message = "Length cannot exceed 500 cm")
    BigDecimal lengthCm,
    @DecimalMin(value = "0.01", message = "Width must be greater than zero")
    @DecimalMax(value = "500.00", message = "Width cannot exceed 500 cm")
    BigDecimal widthCm,
    @DecimalMin(value = "0.01", message = "Height must be greater than zero")
    @DecimalMax(value = "500.00", message = "Height cannot exceed 500 cm")
    BigDecimal heightCm,
    @Size(max = 64, message = "Origin zone must be at most 64 characters")
    String originZone,
    @Size(max = 64, message = "Destination zone must be at most 64 characters")
    String destinationZone,
    @Size(max = 10, message = "Currency must be at most 10 characters")
    String currency
) {
  public boolean hasDimensions() {
    return lengthCm != null && widthCm != null && heightCm != null;
  }
}
