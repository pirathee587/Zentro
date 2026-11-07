package com.zentro.parcelpricing.pricing.dto;

import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import com.zentro.parcelpricing.validation.CodePointSize;
import java.math.BigDecimal;
import com.zentro.parcelpricing.validation.ParcelWeight;
import com.zentro.parcelpricing.validation.StrictDecimalDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.Digits;

public record FeeEstimateRequest(
    @CodePointSize(max = 100, message = "Pickup city must be at most 100 characters")
    @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
    String pickupCity,
    @CodePointSize(max = 100, message = "Delivery city must be at most 100 characters")
    @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
    String deliveryCity,
    DestinationType destinationType,
    ParcelSize parcelSize,
    @ParcelWeight
    @JsonDeserialize(using = StrictDecimalDeserializer.class)
    BigDecimal weightKg,
    @CodePointSize(max = 100, message = "Parcel category must be at most 100 characters")
    @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
    String parcelCategory,
    Boolean isExpress,
    @DecimalMin(value = "0.00", message = "Distance cannot be negative")
    @DecimalMax(value = "2000.00", message = "Distance cannot exceed 2000 km")
    @Digits(integer = 4, fraction = 4, message = "Use at most 4 decimal places.")
    @JsonDeserialize(using = StrictDecimalDeserializer.class)
    BigDecimal distanceKm,
    @DecimalMin(value = "0.01", message = "Length must be greater than zero")
    @DecimalMax(value = "500.00", message = "Length cannot exceed 500 cm")
    @Digits(integer = 4, fraction = 4, message = "Use at most 4 decimal places.")
    @JsonDeserialize(using = StrictDecimalDeserializer.class)
    BigDecimal lengthCm,
    @DecimalMin(value = "0.01", message = "Width must be greater than zero")
    @DecimalMax(value = "500.00", message = "Width cannot exceed 500 cm")
    @Digits(integer = 4, fraction = 4, message = "Use at most 4 decimal places.")
    @JsonDeserialize(using = StrictDecimalDeserializer.class)
    BigDecimal widthCm,
    @DecimalMin(value = "0.01", message = "Height must be greater than zero")
    @DecimalMax(value = "500.00", message = "Height cannot exceed 500 cm")
    @Digits(integer = 4, fraction = 4, message = "Use at most 4 decimal places.")
    @JsonDeserialize(using = StrictDecimalDeserializer.class)
    BigDecimal heightCm,
    @CodePointSize(max = 64, message = "Origin zone must be at most 64 characters")
    @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
    String originZone,
    @CodePointSize(max = 64, message = "Destination zone must be at most 64 characters")
    @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
    String destinationZone,
    @CodePointSize(max = 10, message = "Currency must be at most 10 characters")
    @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
    String currency
) {
  public boolean hasDimensions() {
    return lengthCm != null && widthCm != null && heightCm != null;
  }
}
