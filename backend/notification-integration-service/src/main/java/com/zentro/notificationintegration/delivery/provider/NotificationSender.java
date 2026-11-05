package com.zentro.notificationintegration.delivery.provider;

/** Delivers a notification on one channel. Throws when the channel did not accept it. */
public interface NotificationSender {

  /** The channel name stored with each attempt, for example MOCK_SMS. */
  String channel();

  void send(OutgoingNotification notification) throws NotificationSendException;
}
