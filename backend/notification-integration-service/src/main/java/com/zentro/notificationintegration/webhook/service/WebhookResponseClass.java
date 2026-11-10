package com.zentro.notificationintegration.webhook.service;

/**
 * High-level HTTP response and outcome classification for webhook delivery attempts.
 * Distinguishes successful responses, retryable failures, and non-retryable failures.
 */
public enum WebhookResponseClass {
  SUCCESSFUL,
  RETRYABLE_FAILURE,
  NON_RETRYABLE_FAILURE
}

