package com.zentro.notificationintegration.webhook.dto.response;

import com.zentro.notificationintegration.webhook.entity.WebhookSubscription;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Returned once when an endpoint is registered or its secret is rotated: the configuration plus the new signing
 * secret. The secret is never shown again; the response is sent with {@code Cache-Control: no-store}.
 */
public record WebhookSecretResponse(
    UUID id,
    String url,
    boolean active,
    String secretPrefix,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    String secret
) {
  public static WebhookSecretResponse from(WebhookSubscription subscription, String secret) {
    return new WebhookSecretResponse(subscription.id(), subscription.url(), subscription.active(),
        subscription.secretPrefix(), subscription.createdAt(), subscription.updatedAt(), secret);
  }

  @Override
  public String toString() {
    return "WebhookSecretResponse[id=" + id + ", secretPrefix=" + secretPrefix + "]";
  }
}
