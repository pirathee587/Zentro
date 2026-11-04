package com.zentro.parcelpricing.dto;

import com.zentro.parcelpricing.domain.MerchantOrder;
import com.zentro.parcelpricing.domain.ParcelSize;
import com.zentro.parcelpricing.domain.ParcelStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Response of POST /api/v1/merchant/orders for new (201) or replayed (200) orders. */
public record MerchantOrderResponse(
    UUID id,
    String trackingRef,
    String externalOrderRef,
    String merchantId,
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

  public static MerchantOrderResponse from(MerchantOrder order) {
    return new MerchantOrderResponse(
        order.id(),
        order.trackingRef(),
        order.externalOrderRef(),
        order.merchantId(),
        order.status(),
        order.fee(),
        order.currency(),
        order.estimatedDays(),
        order.createdAt(),
        toContact(order.sender()),
        toContact(order.recipient()),
        new Parcel(order.weightKg(), order.category(), order.size(), order.description()));
  }

  private static Contact toContact(MerchantOrder.Contact contact) {
    return new Contact(contact.name(), contact.phone(), contact.address(), contact.city());
  }
}
