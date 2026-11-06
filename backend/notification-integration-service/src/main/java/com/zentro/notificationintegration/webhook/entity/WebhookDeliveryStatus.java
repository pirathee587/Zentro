package com.zentro.notificationintegration.webhook.entity;

/**
 * State of one webhook delivery. PENDING and RETRY are still open; SUCCEEDED and FAILED are final.
 * A FAILED delivery is the dead-letter record: it is kept with its last response code and error.
 */
public enum WebhookDeliveryStatus {
  PENDING,
  SUCCEEDED,
  RETRY,
  FAILED
}
