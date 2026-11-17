package com.zentro.parcelpricing.parcel.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.zentro.parcelpricing.parcel.entity.ParcelBooking;

/** Owner-authorized booking view; price/status/contact data are read from S2's persisted booking. */
public record CustomerParcelView(UUID id, String trackingRef, String status, BigDecimal fee, String currency,
    int estimatedDays, OffsetDateTime createdAt, String destinationType, boolean isExpress, ParcelBookingResponse.Contact sender,
    ParcelBookingResponse.Contact recipient, ParcelBookingResponse.Parcel parcel) {
  public static CustomerParcelView from(ParcelBooking booking) {
    var response = ParcelBookingResponse.from(booking);
    return new CustomerParcelView(response.id(), response.trackingRef(), response.status().name(), response.fee(), response.currency(),
        response.estimatedDays(), response.createdAt(), booking.destinationType()==null?null:booking.destinationType().name(),
        booking.express(), response.sender(), response.recipient(), response.parcel());
  }
}
