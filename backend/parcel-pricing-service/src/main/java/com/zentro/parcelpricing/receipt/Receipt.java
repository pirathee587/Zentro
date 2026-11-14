package com.zentro.parcelpricing.receipt;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable billing snapshot model representing an official delivery receipt.
 * The fee and currency are strictly populated from the persisted booking record without repricing.
 */
public record Receipt(
    UUID id,
    String receiptNumber,
    UUID parcelId,
    String trackingRef,
    String customerSubject,
    String merchantId,
    Contact sender,
    Contact recipient,
    BigDecimal fee,
    String currency,
    String paymentStatus,
    String paymentMethod,
    OffsetDateTime issuedAt,
    String description,
    String category
) {
  public record Contact(String name, String phone, String address, String city) {}
}
