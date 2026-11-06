package com.zentro.notificationintegration.webhook.exception;

/** The calling merchant has no (active) webhook subscription (404). Other merchants' data is never looked up. */
public class WebhookNotFoundException extends RuntimeException {
  public WebhookNotFoundException(String message) {
    super(message);
  }
}
