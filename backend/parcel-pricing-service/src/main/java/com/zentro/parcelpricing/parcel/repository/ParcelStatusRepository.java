package com.zentro.parcelpricing.parcel.repository;

import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.entity.ParcelStatusChange;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JdbcTemplate access for parcel status changes. Every method must run inside the caller's transaction,
 * so the row lock, the status update, the history row and the outbox event commit or roll back together.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ParcelStatusRepository {

  /** The parts of a parcel row needed to change its status, read under a row lock. */
  public record LockedParcel(UUID id, String trackingRef, ParcelStatus status, long version) {}

  private final JdbcTemplate jdbc;

  public ParcelStatusRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Reads the parcel and locks its row until the transaction ends; empty when it does not exist. */
  public Optional<LockedParcel> lockForUpdate(UUID parcelId) {
    List<LockedParcel> rows = jdbc.query(
        "SELECT id, tracking_ref, status, version FROM parcel.parcel WHERE id = ? FOR UPDATE",
        (rs, rowNum) -> new LockedParcel(
            rs.getObject("id", UUID.class),
            rs.getString("tracking_ref"),
            ParcelStatus.valueOf(rs.getString("status")),
            rs.getLong("version")),
        parcelId);
    return rows.stream().findFirst();
  }

  /**
   * Sets the new status and increases the version, only if the row still has {@code expectedVersion}.
   *
   * @return the new version
   * @throws OptimisticLockingFailureException when the row changed since it was read
   */
  public long updateStatus(UUID parcelId, ParcelStatus newStatus, long expectedVersion, OffsetDateTime changedAt) {
    int updated = jdbc.update("""
        UPDATE parcel.parcel
           SET status = ?, updated_at = ?, version = version + 1
         WHERE id = ? AND version = ?
        """, newStatus.name(), changedAt, parcelId, expectedVersion);
    if (updated != 1) {
      throw new OptimisticLockingFailureException("Parcel " + parcelId + " changed while its status was being updated");
    }
    return expectedVersion + 1;
  }

  /** Appends one row to the immutable status history. */
  public void insertHistory(ParcelStatusChange change) {
    jdbc.update("""
        INSERT INTO parcel.parcel_status_history
            (parcel_id, from_status, to_status, reason, note, actor_subject, actor_role, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        change.parcelId(),
        change.previousStatus().name(),
        change.status().name(),
        change.reason(),
        change.note(),
        change.actorSubject(),
        change.actorRole(),
        change.occurredAt());
  }

  /** Stores one event to be published later. {@code payloadJson} must be the full event envelope. */
  public void insertOutboxEvent(UUID eventId, UUID aggregateId, String eventType, String payloadJson,
      OffsetDateTime occurredAt) {
    jdbc.update("""
        INSERT INTO parcel.outbox_event (event_id, aggregate_id, event_type, payload, occurred_at)
        VALUES (?, ?, ?, ?::jsonb, ?)
        """, eventId, aggregateId, eventType, payloadJson, occurredAt);
  }
}
