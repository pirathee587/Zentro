package com.zentro.notificationintegration.webhook.service;

/**
 * Categorization of terminal (permanent) webhook delivery failures.
 * Distinguishes retry exhaustion from permanent status codes and configuration/endpoint rejections.
 */
public enum WebhookTerminalCategory {
  EXHAUSTED_RETRIES,
  NON_RETRYABLE_STATUS,
  ENDPOINT_NOT_ALLOWED,
  SUBSCRIPTION_DISABLED,
  SECRET_UNAVAILABLE
}

