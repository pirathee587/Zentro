package com.zentro.notificationintegration.webhook.entity;

import com.zentro.notificationintegration.webhook.service.WebhookResponseClass;
import com.zentro.notificationintegration.webhook.service.WebhookRetryPolicy;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One parcel event sent (or to be sent) to one subscription. Row of notification.webhook_delivery, without the
 * stored payload, which is only read when sending.
 *
 * @param responseStatus HTTP status of the latest attempt; null when no response was received
 * @param lastError      short error of the latest failed attempt; never the response body
 */
public record WebhookDelivery(
    UUID id,
    UUID subscriptionId,
    UUID eventId,
    String eventType,
    UUID parcelId,
    WebhookDeliveryStatus status,
    int attempts,
    Integer responseStatus,
    String lastError,
    OffsetDateTime nextAttemptAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
  /**
   * High-level response classification of the latest attempt.
   * Null if the delivery is still pending and has not been attempted yet.
   */
  public String responseClass() {
    if (attempts == 0) {
      return null;
    }
    WebhookResponseClass rc = WebhookRetryPolicy.classifyResponse(responseStatus, lastError);
    return rc == null ? null : rc.name();
  }
}
