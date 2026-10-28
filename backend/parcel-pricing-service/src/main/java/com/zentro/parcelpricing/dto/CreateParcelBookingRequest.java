package com.zentro.parcelpricing.dto;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.ParcelSize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
    @Size(max = 500, message = "Notes must be at most 500 characters.") String notes
) {
  /** Sri Lankan number (+94 or 0 followed by 9 digits); spaces and dashes are ignored. */
  public static final String PHONE_PATTERN = "^[\\s-]*(\\+94|0)([\\s-]*\\d){9}[\\s-]*$";
  public static final String PHONE_MESSAGE = "Enter a Sri Lankan number, e.g. 0771234567 or +94771234567.";
  /** At least 8 characters once leading and trailing spaces are removed. */
  public static final String ADDRESS_PATTERN = "(?s)^\\s*\\S.{6,}\\S\\s*$";
  public static final String ADDRESS_MESSAGE = "Address must be between 8 and 255 characters.";
  public static final String WEIGHT_MESSAGE = "Weight must be between 0.1 and 20 kg.";

  public record Contact(
      @NotBlank(message = "Name is required.")
      @Size(max = 100, message = "Name must be at most 100 characters.")
      String name,

      @NotBlank(message = PHONE_MESSAGE)
      @Pattern(regexp = PHONE_PATTERN, message = PHONE_MESSAGE)
      String phone,

      @NotBlank(message = ADDRESS_MESSAGE)
      @Size(max = 255, message = ADDRESS_MESSAGE)
      @Pattern(regexp = ADDRESS_PATTERN, message = ADDRESS_MESSAGE)
      String address,

      @Size(max = 100, message = "City must be at most 100 characters.")
      String city
  ) {}

  public record ParcelDetails(
      @NotNull(message = WEIGHT_MESSAGE)
      @DecimalMin(value = "0.1", message = WEIGHT_MESSAGE)
      @DecimalMax(value = "20", message = WEIGHT_MESSAGE)
      BigDecimal weightKg,

      @NotBlank(message = "Category is required.")
      @Size(max = 40, message = "Category must be at most 40 characters.")
      String category,

      ParcelSize size,

      @Size(max = 255, message = "Description must be at most 255 characters.")
      String description
  ) {}
}
