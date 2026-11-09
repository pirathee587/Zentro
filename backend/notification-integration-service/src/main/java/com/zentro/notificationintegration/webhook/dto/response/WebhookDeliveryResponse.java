package com.zentro.notificationintegration.webhook.dto.response;

import com.zentro.notificationintegration.webhook.entity.WebhookDelivery;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One delivery as shown to its merchant: outcome, attempts, the latest response code and response class. No payload. */
public record WebhookDeliveryResponse(
    UUID id,
    UUID eventId,
    String eventType,
    UUID parcelId,
    String status,
    int attempts,
    Integer responseStatus,
    String responseClass,
    String lastError,
    OffsetDateTime nextAttemptAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
  public WebhookDeliveryResponse(
      UUID id,
      UUID eventId,
      String eventType,
      UUID parcelId,
      String status,
      int attempts,
      Integer responseStatus,
      String lastError,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt
  ) {
    this(id, eventId, eventType, parcelId, status, attempts, responseStatus, null, lastError, nextAttemptAt, createdAt, updatedAt);
  }

  public static WebhookDeliveryResponse from(WebhookDelivery delivery) {
    return new WebhookDeliveryResponse(delivery.id(), delivery.eventId(), delivery.eventType(), delivery.parcelId(),
        delivery.status().name(), delivery.attempts(), delivery.responseStatus(), delivery.responseClass(),
        delivery.lastError(), delivery.nextAttemptAt(), delivery.createdAt(), delivery.updatedAt());
  }
}
