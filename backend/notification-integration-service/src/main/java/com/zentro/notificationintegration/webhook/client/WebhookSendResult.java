package com.zentro.notificationintegration.webhook.client;

/**
 * Outcome of one HTTP attempt. Either a response status (the body is never kept) or a short network error.
 *
 * @param status  HTTP status code, or null when no response was received
 * @param error   short error category when no response was received (for example "timeout"), otherwise null
 */
public record WebhookSendResult(Integer status, String error) {

  public static WebhookSendResult response(int status) {
    return new WebhookSendResult(status, null);
  }

  public static WebhookSendResult timeout() {
    return new WebhookSendResult(null, "timeout");
  }

  public static WebhookSendResult networkError(String category) {
    return new WebhookSendResult(null, category);
  }

  public boolean hasResponse() {
    return status != null;
  }
}
