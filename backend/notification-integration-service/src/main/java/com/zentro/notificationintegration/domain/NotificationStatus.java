package com.zentro.notificationintegration.domain;

/** Outcome of a notification row in notification.notification_attempt. */
public enum NotificationStatus {
  /** Created and about to be sent for the first time. */
  PENDING,
  /** The sender accepted the notification. */
  SENT,
  /** A send failed; it will be tried again at next_attempt_at. */
  RETRY,
  /** All attempts failed; no further retries. */
  FAILED,
  /** The parcel status does not notify anyone (for example IN_TRANSIT). */
  SKIPPED
}
