package com.zentro.notificationintegration.webhook.dto.response;

import java.util.List;

/** A page of the merchant's own deliveries, newest first. {@code page} starts at 0. */
public record WebhookDeliveryPage(
    List<WebhookDeliveryResponse> items,
    int page,
    int size,
    long total
) {}
