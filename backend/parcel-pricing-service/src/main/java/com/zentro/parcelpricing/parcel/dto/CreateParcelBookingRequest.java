package com.zentro.parcelpricing.parcel.dto;

import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import jakarta.validation.Valid;
import com.zentro.parcelpricing.validation.Address;
import com.zentro.parcelpricing.validation.ParcelWeight;
import com.zentro.parcelpricing.validation.StrictDecimalDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.zentro.parcelpricing.validation.CodePointSize;
import java.math.BigDecimal;

/**
 * Body of POST /api/v1/parcels. Mirrors the frontend CreateDeliveryRequest and its form rules.
 * The fee, status, tracking reference and customer are never taken from the client.
 */
public record CreateParcelBookingRequest(
    @NotNull(message = "Sender details are required.") @Valid Contact sender,
    @NotNull(message = "Recipient details are required.") @Valid Contact recipient,
    @NotNull(message = "Parcel details are required.") @Valid ParcelDetails parcel,
    DestinationType destinationType,
    Boolean isExpress,
    @CodePointSize(max = 500, message = "Notes must be at most 500 characters.") @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class) String notes
) {
  public static final String WEIGHT_MESSAGE = ParcelWeight.MESSAGE;

  public record Contact(
      @NotBlank(message = "Name is required.")
      @CodePointSize(max = 100, message = "Name must be at most 100 characters.")
      @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
      String name,

      @com.zentro.parcelpricing.validation.SriLankanPhone
      @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
      String phone,

      @Address
      @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
      String address,

      @CodePointSize(max = 100, message = "City must be at most 100 characters.")
      @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
      String city
  ) {}

  public record ParcelDetails(
      @NotNull(message = WEIGHT_MESSAGE)
      @ParcelWeight
      @JsonDeserialize(using = StrictDecimalDeserializer.class)
      BigDecimal weightKg,

      @NotBlank(message = "Category is required.")
      @CodePointSize(max = 40, message = "Category must be at most 40 characters.")
      @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
      String category,

      ParcelSize size,

      @CodePointSize(max = 255, message = "Description must be at most 255 characters.")
      @JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
      String description
  ) {}
}
