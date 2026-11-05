package com.zentro.notificationintegration.webhook.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A merchant's webhook endpoint (one per merchant). Row of notification.webhook_subscription.
 *
 * @param merchantId       merchant account id (Keycloak subject), the same value as payload.merchantId in events
 * @param secretCiphertext the signing secret, encrypted; never returned by the API or logged
 * @param secretPrefix     the first characters of the secret, safe to show so the merchant can tell secrets apart
 */
public record WebhookSubscription(
    UUID id,
    String merchantId,
    String url,
    String secretCiphertext,
    String secretPrefix,
    boolean active,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
