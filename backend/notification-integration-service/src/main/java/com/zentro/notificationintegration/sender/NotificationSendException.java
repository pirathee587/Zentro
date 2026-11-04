package com.zentro.notificationintegration.sender;

/** The channel did not accept a notification. The message is a short reason without the notification text. */
public class NotificationSendException extends Exception {
  public NotificationSendException(String reason) {
    super(reason);
  }
}
