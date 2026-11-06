package com.zentro.notificationintegration.webhook.dto.response;

import com.zentro.notificationintegration.webhook.entity.WebhookSubscription;
import java.time.OffsetDateTime;
import java.util.UUID;

/** The merchant's webhook configuration. Never contains the secret, only its first characters. */
public record WebhookSubscriptionResponse(
    UUID id,
    String url,
    boolean active,
    String secretPrefix,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
  public static WebhookSubscriptionResponse from(WebhookSubscription subscription) {
    return new WebhookSubscriptionResponse(subscription.id(), subscription.url(), subscription.active(),
        subscription.secretPrefix(), subscription.createdAt(), subscription.updatedAt());
  }
}
