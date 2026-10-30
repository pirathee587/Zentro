package com.zentro.parcelpricing.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A customer delivery booking as stored in parcel.parcel.
 * id, createdAt and updatedAt are null until the row has been inserted.
 */
public record ParcelBooking(
    UUID id,
    String trackingRef,
    String customerSubject,
    Contact sender,
    Contact recipient,
    BigDecimal weightKg,
    ParcelSize size,
    String category,
    String description,
    DestinationType destinationType,
    boolean express,
    String notes,
    BigDecimal fee,
    String currency,
    int estimatedDays,
    ParcelStatus status,
    String idempotencyKey,
    String requestHash,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
  public record Contact(String name, String phone, String address, String city) {}

  public ParcelBooking withTrackingRef(String newTrackingRef) {
    return new ParcelBooking(id, newTrackingRef, customerSubject, sender, recipient, weightKg, size, category,
        description, destinationType, express, notes, fee, currency, estimatedDays, status, idempotencyKey,
        requestHash, createdAt, updatedAt);
  }
}
