package com.zentro.notificationintegration.webhook.exception;

/**
 * The webhook URL breaks a rule (not https, user info, blocked address, too long...). Shown to the merchant as a
 * field error on {@code url} (422); at send time the delivery fails permanently. The message names the rule only.
 */
public class InvalidWebhookUrlException extends RuntimeException {
  public InvalidWebhookUrlException(String message) {
    super(message);
  }
}
