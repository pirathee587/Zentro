package com.zentro.dispatchcourier.dashboard;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Authoritative current ownership: cancelled rows are absent, reassigned rows have different IDs. */
@Repository
public class CourierAssignmentSnapshotRepository {
  public static final int MAX_ASSIGNMENTS = 5000;
  public record Assignment(UUID parcelId, Instant assignedAt, String assignmentState,
      UUID proofId, String completionState, Instant completedAt) {}
  private final JdbcTemplate jdbc;
  public CourierAssignmentSnapshotRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Transactional(readOnly = true)
  public List<Assignment> snapshot(UUID userId, UUID profileId) {
    List<Assignment> rows = jdbc.query("""
        SELECT a.parcel_id, a.assigned_at, p.id AS proof_id, p.completion_state, p.completed_at
        FROM dispatch.delivery_assignment a
        LEFT JOIN dispatch.proof_of_delivery p ON p.parcel_id = a.parcel_id
        WHERE a.courier_user_id = ? AND a.courier_profile_id = ?
        ORDER BY a.assigned_at, a.parcel_id LIMIT 5001
        """, (rs, index) -> {
          OffsetDateTime completed = rs.getObject("completed_at", OffsetDateTime.class);
          return new Assignment(rs.getObject("parcel_id", UUID.class),
              rs.getObject("assigned_at", OffsetDateTime.class).toInstant(), "ASSIGNED",
              rs.getObject("proof_id", UUID.class), rs.getString("completion_state"),
              completed == null ? null : completed.toInstant());
        }, userId, profileId);
    if (rows.size() > MAX_ASSIGNMENTS)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Assignment snapshot limit exceeded.");
    return rows;
  }
}
