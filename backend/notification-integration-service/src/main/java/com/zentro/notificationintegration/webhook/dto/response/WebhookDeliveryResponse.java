package com.zentro.notificationintegration.webhook.dto.response;

import com.zentro.notificationintegration.webhook.entity.WebhookDelivery;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One delivery as shown to its merchant: outcome, attempts and the latest response code. No payload. */
public record WebhookDeliveryResponse(
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
  public static WebhookDeliveryResponse from(WebhookDelivery delivery) {
    return new WebhookDeliveryResponse(delivery.id(), delivery.eventId(), delivery.eventType(), delivery.parcelId(),
        delivery.status().name(), delivery.attempts(), delivery.responseStatus(), delivery.lastError(),
        delivery.nextAttemptAt(), delivery.createdAt(), delivery.updatedAt());
  }
}
