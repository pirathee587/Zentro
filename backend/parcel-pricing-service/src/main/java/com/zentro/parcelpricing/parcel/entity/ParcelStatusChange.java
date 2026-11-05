package com.zentro.parcelpricing.parcel.entity;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One accepted parcel status change: what changed, who made it, why and when.
 * It is stored once as a history row and described once by the status event.
 *
 * @param actorSubject token subject of the user or service that made the change (stored, never published)
 * @param actorRole    role the change was made with, for example DISPATCHER or SERVICE
 * @param reason       required when the new status is FAILED
 * @param note         free text for operators; stored only, never published or logged
 */
public record ParcelStatusChange(
    UUID parcelId,
    String trackingRef,
    ParcelStatus previousStatus,
    ParcelStatus status,
    String reason,
    String note,
    String actorSubject,
    String actorRole,
    OffsetDateTime occurredAt
) {
  public ParcelStatusChange {
    Objects.requireNonNull(parcelId, "parcelId");
    Objects.requireNonNull(trackingRef, "trackingRef");
    Objects.requireNonNull(previousStatus, "previousStatus");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(actorSubject, "actorSubject");
    Objects.requireNonNull(actorRole, "actorRole");
    Objects.requireNonNull(occurredAt, "occurredAt");
  }

  /** Leaves out the note and the actor subject so this record is safe to log. */
  @Override
  public String toString() {
    return "ParcelStatusChange[parcelId=" + parcelId + ", trackingRef=" + trackingRef
        + ", previousStatus=" + previousStatus + ", status=" + status + ", actorRole=" + actorRole
        + ", occurredAt=" + occurredAt + "]";
  }
}
