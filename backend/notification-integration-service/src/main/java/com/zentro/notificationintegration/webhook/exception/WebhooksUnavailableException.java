package com.zentro.notificationintegration.webhook.exception;

/** Webhooks cannot be used right now, for example because no encryption key is configured (503). */
public class WebhooksUnavailableException extends RuntimeException {
  public WebhooksUnavailableException(String message) {
    super(message);
  }
}
