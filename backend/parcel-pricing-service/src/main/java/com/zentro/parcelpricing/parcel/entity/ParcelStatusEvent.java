package com.zentro.parcelpricing.parcel.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** Existing v1 wire envelope; schemaVersion=1 remains a Kafka header for compatibility. */
public record ParcelStatusEvent(UUID eventId, String eventType, String occurredAt, UUID aggregateId,
    Payload payload) {
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(String trackingRef, String previousStatus, String status, String actorRole,
      String occurredAt, String failureReason, String merchantId) {}
}
