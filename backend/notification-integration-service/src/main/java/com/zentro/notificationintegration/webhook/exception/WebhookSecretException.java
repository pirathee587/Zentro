package com.zentro.notificationintegration.webhook.exception;

/**
 * A stored signing secret cannot be decrypted (wrong or changed key, or damaged data). The message never contains
 * the secret, the key or the ciphertext.
 */
public class WebhookSecretException extends RuntimeException {
  public WebhookSecretException(String message, Throwable cause) {
    super(message, cause);
  }
}
