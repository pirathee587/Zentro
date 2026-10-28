package com.zentro.parcelpricing.dto;

import com.zentro.parcelpricing.domain.ParcelBooking;
import com.zentro.parcelpricing.domain.ParcelSize;
import com.zentro.parcelpricing.domain.ParcelStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response of POST /api/v1/parcels for a new (201) or replayed (200) booking. */
public record ParcelBookingResponse(
    UUID id,
    String trackingRef,
    ParcelStatus status,
    BigDecimal fee,
    String currency,
    int estimatedDays,
    OffsetDateTime createdAt,
    Contact sender,
    Contact recipient,
    Parcel parcel
) {
  public record Contact(String name, String phone, String address, String city) {}

  public record Parcel(BigDecimal weightKg, String category, ParcelSize size, String description) {}

  public static ParcelBookingResponse from(ParcelBooking booking) {
    return new ParcelBookingResponse(
        booking.id(),
        booking.trackingRef(),
        booking.status(),
        booking.fee(),
        booking.currency(),
        booking.estimatedDays(),
        booking.createdAt(),
        toContact(booking.sender()),
        toContact(booking.recipient()),
        new Parcel(booking.weightKg(), booking.category(), booking.size(), booking.description()));
  }

  private static Contact toContact(ParcelBooking.Contact contact) {
    return new Contact(contact.name(), contact.phone(), contact.address(), contact.city());
  }
}
