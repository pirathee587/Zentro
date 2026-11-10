package com.zentro.parcelpricing.parcel.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest.Contact;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest.ParcelDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.zentro.parcelpricing.validation.CodePointSize;

/**
 * Body of POST /api/v1/merchant/orders.
 * Accepts externalOrderRef (or external_order_ref) to ensure idempotent merchant ordering.
 */
public record CreateMerchantOrderRequest(
    @JsonAlias({"external_order_ref", "externalOrderRef"})
    @JsonProperty("externalOrderRef")
    @NotBlank(message = "External order reference is required.")
    @CodePointSize(max = 100, message = "External order reference must be at most 100 characters.")
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class)
    String externalOrderRef,

    @NotNull(message = "Sender details are required.") @Valid Contact sender,
    @NotNull(message = "Recipient details are required.") @Valid Contact recipient,
    @NotNull(message = "Parcel details are required.") @Valid ParcelDetails parcel,
    DestinationType destinationType,
    Boolean isExpress,
    @CodePointSize(max = 500, message = "Notes must be at most 500 characters.")
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.zentro.parcelpricing.validation.StrictTextDeserializer.class) String notes
) {
  public CreateParcelBookingRequest toBookingRequest() {
    return new CreateParcelBookingRequest(sender, recipient, parcel, destinationType, isExpress, notes);
  }
}
