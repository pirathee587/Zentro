package com.zentro.notificationintegration.webhook.exception;

/**
 * The webhook host name cannot be resolved right now. Rejected at registration (422); at send time it is treated
 * as a network error and retried, because DNS problems are often temporary.
 */
public class UnresolvableWebhookHostException extends InvalidWebhookUrlException {
  public UnresolvableWebhookHostException(String message) {
    super(message);
  }
}
