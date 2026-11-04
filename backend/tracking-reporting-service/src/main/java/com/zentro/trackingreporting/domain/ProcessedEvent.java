package com.zentro.trackingreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_event", schema = "tracking")
@IdClass(ProcessedEventId.class)
public class ProcessedEvent {

  @Id
  @Column(name = "consumer_name", nullable = false, length = 100)
  private String consumerName;

  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "processed_at", nullable = false)
  private Instant processedAt;

  public ProcessedEvent() {
    this.processedAt = Instant.now();
  }

  public ProcessedEvent(String consumerName, UUID eventId) {
    this.consumerName = consumerName;
    this.eventId = eventId;
    this.processedAt = Instant.now();
  }

  public String getConsumerName() { return consumerName; }
  public void setConsumerName(String consumerName) { this.consumerName = consumerName; }

  public UUID getEventId() { return eventId; }
  public void setEventId(UUID eventId) { this.eventId = eventId; }

  public Instant getProcessedAt() { return processedAt; }
  public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}

