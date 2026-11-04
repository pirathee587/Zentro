package com.zentro.parcelpricing.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant order / delivery booking stored in parcel.parcel.
 * id, createdAt, and updatedAt are null until the row has been inserted.
 */
public record MerchantOrder(
    UUID id,
    String trackingRef,
    String merchantId,
    String externalOrderRef,
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
    String requestHash,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
  public record Contact(String name, String phone, String address, String city) {}

  public MerchantOrder withTrackingRef(String newTrackingRef) {
    return new MerchantOrder(id, newTrackingRef, merchantId, externalOrderRef, sender, recipient, weightKg,
        size, category, description, destinationType, express, notes, fee, currency, estimatedDays,
        status, requestHash, createdAt, updatedAt);
  }
}
