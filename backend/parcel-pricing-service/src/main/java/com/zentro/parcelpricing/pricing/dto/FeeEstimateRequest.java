package com.zentro.parcelpricing.pricing.dto;

import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
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
    @Digits(integer = 5, fraction = 2, message = "Weight must have at most 2 decimal places")
    BigDecimal weightKg,
    @Size(max = 100, message = "Parcel category must be at most 100 characters")
    String parcelCategory,
    Boolean isExpress,
    @DecimalMin(value = "0.00", message = "Distance cannot be negative")
    @DecimalMax(value = "2000.00", message = "Distance cannot exceed 2000 km")
    @Digits(integer = 6, fraction = 2, message = "Distance must have at most 2 decimal places")
    BigDecimal distanceKm,
    @DecimalMin(value = "0.01", message = "Length must be greater than zero")
    @DecimalMax(value = "500.00", message = "Length cannot exceed 500 cm")
    @Digits(integer = 5, fraction = 2, message = "Length must have at most 2 decimal places")
    BigDecimal lengthCm,
    @DecimalMin(value = "0.01", message = "Width must be greater than zero")
    @DecimalMax(value = "500.00", message = "Width cannot exceed 500 cm")
    @Digits(integer = 5, fraction = 2, message = "Width must have at most 2 decimal places")
    BigDecimal widthCm,
    @DecimalMin(value = "0.01", message = "Height must be greater than zero")
    @DecimalMax(value = "500.00", message = "Height cannot exceed 500 cm")
    @Digits(integer = 5, fraction = 2, message = "Height must have at most 2 decimal places")
    BigDecimal heightCm,
    @Size(max = 64, message = "Origin zone must be at most 64 characters")
    String originZone,
    @Size(max = 64, message = "Destination zone must be at most 64 characters")
    String destinationZone,
    @Pattern(regexp = "^[A-Za-z]{3}$", message = "Currency must be a 3-letter ISO code")
    String currency
) {
  public boolean hasDimensions() {
    return lengthCm != null && widthCm != null && heightCm != null;
  }
}
