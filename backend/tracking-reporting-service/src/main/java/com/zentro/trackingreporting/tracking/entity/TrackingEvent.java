package com.zentro.trackingreporting.tracking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tracking_event", schema = "tracking")
public class TrackingEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private UUID id;

  @Column(name = "event_id")
  private UUID eventId;

  @Column(name = "tracking_ref", nullable = false, length = 32)
  private String trackingRef;

  @Column(name = "parcel_id")
  private UUID parcelId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 32)
  private TrackingStatus status;

  @Column(name = "previous_status", length = 32)
  private String previousStatus;

  @Column(name = "actor_role", nullable = false, length = 32)
  private String actorRole;

  @Column(name = "title", nullable = false, length = 100)
  private String title;

  @Column(name = "description", length = 255)
  private String description;

  @Column(name = "failure_reason", length = 200)
  private String failureReason;

  @Column(name = "location", length = 100)
  private String location;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public TrackingEvent() {
    this.occurredAt = Instant.now();
    this.createdAt = Instant.now();
    this.actorRole = "SYSTEM";
  }

  public TrackingEvent(String trackingRef, TrackingStatus status, String title, String description, Instant occurredAt) {
    this.trackingRef = trackingRef;
    this.status = status;
    this.title = title;
    this.description = description;
    this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
    this.createdAt = Instant.now();
    this.actorRole = "SYSTEM";
  }

  // Getters and setters
  public UUID getId() { return id; }
  public void setId(UUID id) { this.id = id; }

  public UUID getEventId() { return eventId; }
  public void setEventId(UUID eventId) { this.eventId = eventId; }

  public String getTrackingRef() { return trackingRef; }
  public void setTrackingRef(String trackingRef) { this.trackingRef = trackingRef; }

  public UUID getParcelId() { return parcelId; }
  public void setParcelId(UUID parcelId) { this.parcelId = parcelId; }

  public TrackingStatus getStatus() { return status; }
  public void setStatus(TrackingStatus status) { this.status = status; }

  public String getPreviousStatus() { return previousStatus; }
  public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }

  public String getActorRole() { return actorRole; }
  public void setActorRole(String actorRole) { this.actorRole = actorRole; }

  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }

  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }

  public String getFailureReason() { return failureReason; }
  public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

  public String getLocation() { return location; }
  public void setLocation(String location) { this.location = location; }

  public Instant getOccurredAt() { return occurredAt; }
  public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

