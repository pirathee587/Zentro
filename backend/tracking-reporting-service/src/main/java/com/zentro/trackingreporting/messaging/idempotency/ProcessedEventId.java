package com.zentro.trackingreporting.messaging.idempotency;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class ProcessedEventId implements Serializable {
  private String consumerName;
  private UUID eventId;

  public ProcessedEventId() {}

  public ProcessedEventId(String consumerName, UUID eventId) {
    this.consumerName = consumerName;
    this.eventId = eventId;
  }

  public String getConsumerName() { return consumerName; }
  public void setConsumerName(String consumerName) { this.consumerName = consumerName; }

  public UUID getEventId() { return eventId; }
  public void setEventId(UUID eventId) { this.eventId = eventId; }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof ProcessedEventId that)) return false;
    return Objects.equals(consumerName, that.consumerName) && Objects.equals(eventId, that.eventId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(consumerName, eventId);
  }
}

