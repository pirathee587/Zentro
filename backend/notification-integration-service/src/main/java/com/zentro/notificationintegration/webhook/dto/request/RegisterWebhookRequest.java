package com.zentro.notificationintegration.webhook.dto.request;

import com.zentro.notificationintegration.webhook.validation.WebhookUrlValidator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/merchant/webhooks}. The full URL rules (https, blocked addresses) are checked by
 * {@link WebhookUrlValidator}; the server always creates the signing secret.
 */
public record RegisterWebhookRequest(
    @NotBlank(message = "URL is required.")
    @Size(max = WebhookUrlValidator.MAX_LENGTH, message = "URL must be at most 2048 characters.")
    String url
) {}
