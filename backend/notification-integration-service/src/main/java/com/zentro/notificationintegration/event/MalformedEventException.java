package com.zentro.notificationintegration.event;

/** A Kafka message that is not a valid parcel event. The message is a short reason, never the payload. */
public class MalformedEventException extends RuntimeException {
  public MalformedEventException(String reason) {
    super(reason);
  }
}
