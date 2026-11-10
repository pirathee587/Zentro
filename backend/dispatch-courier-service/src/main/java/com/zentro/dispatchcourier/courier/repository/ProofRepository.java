package com.zentro.dispatchcourier.courier.repository;

import com.zentro.security.observability.PropagationContext;
import com.zentro.security.observability.PropagationMetadata;
import com.zentro.dispatchcourier.courier.dto.SubmitProofRequest;
import com.zentro.dispatchcourier.courier.entity.ProofOfDelivery;
import com.zentro.dispatchcourier.exception.ProofException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProofRepository {
  private PropagationContext propagation = PropagationContext.noop();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPropagation(PropagationContext propagation) { this.propagation = propagation; }

  public record DeliverySummary(UUID parcelId, UUID courierId, Instant assignedAt,
      UUID proofId, String completionState) {}
  public record Submission(ProofOfDelivery proof, boolean created) {}
  public record Claim(UUID proofId, UUID parcelId, UUID leaseToken, int attempts, PropagationMetadata propagation) {
    public Claim(UUID proofId, UUID parcelId, UUID leaseToken, int attempts) {
      this(proofId, parcelId, leaseToken, attempts, new PropagationMetadata(proofId.toString(), null, null));
    }
  }
  private final JdbcTemplate jdbc;
  public ProofRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<DeliverySummary> deliveries(UUID profileId, UUID userId) {
    return jdbc.query("""
        SELECT a.parcel_id, a.courier_profile_id, a.assigned_at, p.id AS proof_id, p.completion_state
        FROM dispatch.delivery_assignment a LEFT JOIN dispatch.proof_of_delivery p ON p.parcel_id = a.parcel_id
        WHERE a.courier_profile_id = ? AND a.courier_user_id = ? ORDER BY a.assigned_at DESC, a.parcel_id
        """, (row, index) -> new DeliverySummary(row.getObject("parcel_id", UUID.class),
        row.getObject("courier_profile_id", UUID.class), instant(row, "assigned_at"),
        row.getObject("proof_id", UUID.class), row.getString("completion_state")), profileId, userId);
  }

  public Optional<ProofOfDelivery> find(UUID parcelId) {
    return jdbc.query("SELECT * FROM dispatch.proof_of_delivery WHERE parcel_id = ?",
        (row, index) -> map(row), parcelId).stream().findFirst();
  }

  @Transactional
  public Submission save(UUID parcelId, String subject, SubmitProofRequest request) {
    var metadata = propagation.capture();
    int created = jdbc.update("""
        INSERT INTO dispatch.proof_of_delivery
        (id, parcel_id, recipient_name, notes, proof_reference, submitted_by_subject, correlation_id, traceparent, tracestate)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (parcel_id) DO NOTHING
        """, UUID.randomUUID(), parcelId, request.recipientName(), request.notes(), request.proofReference(), subject, metadata.correlationId(), metadata.traceparent(), metadata.tracestate());
    ProofOfDelivery proof = find(parcelId).orElseThrow();
    if (!proof.sameEvidence(request))
      throw new ProofException(409, "PROOF_CONFLICT", "Proof is already recorded with different evidence. Refresh to view it.");
    return new Submission(proof, created == 1);
  }

  /** One short transaction claims work. The lease survives restarts; HTTP runs after this commits. */
  @Transactional
  public Optional<Claim> claim(int maxAttempts) {
    jdbc.update("""
        UPDATE dispatch.proof_of_delivery SET completion_state = 'BLOCKED', last_error_code = 'RETRY_EXHAUSTED',
          lease_token = NULL WHERE completion_state = 'PENDING' AND attempts >= ? AND next_attempt_at <= now()
        """, maxAttempts);
    UUID token = UUID.randomUUID();
    return jdbc.query("""
        WITH candidate AS (
          SELECT id FROM dispatch.proof_of_delivery
          WHERE completion_state = 'PENDING' AND next_attempt_at <= now() AND attempts < ?
          ORDER BY next_attempt_at, id FOR UPDATE SKIP LOCKED LIMIT 1
        )
        UPDATE dispatch.proof_of_delivery p SET attempts = attempts + 1, lease_token = ?,
          next_attempt_at = now() + interval '60 seconds'
        FROM candidate c WHERE p.id = c.id RETURNING p.id, p.parcel_id, p.attempts, p.correlation_id, p.traceparent, p.tracestate
        """, (row, index) -> new Claim(row.getObject("id", UUID.class), row.getObject("parcel_id", UUID.class),
        token, row.getInt("attempts"), new PropagationMetadata(
            row.getString("correlation_id") == null ? row.getString("id") : row.getString("correlation_id"),
            row.getString("traceparent"), row.getString("tracestate"))), maxAttempts, token).stream().findFirst();
  }

  public void finish(Claim claim, String state, String errorCode, long delaySeconds) {
    jdbc.update("""
        UPDATE dispatch.proof_of_delivery SET completion_state = ?, last_error_code = ?, lease_token = NULL,
          completed_at = CASE WHEN ? = 'COMPLETED' THEN now() ELSE NULL END,
          next_attempt_at = now() + (? * interval '1 second')
        WHERE id = ? AND lease_token = ? AND completion_state = 'PENDING'
        """, state, errorCode, state, delaySeconds, claim.proofId(), claim.leaseToken());
  }

  private static ProofOfDelivery map(ResultSet row) throws SQLException {
    return new ProofOfDelivery(row.getObject("id", UUID.class), row.getObject("parcel_id", UUID.class),
        row.getString("recipient_name"), row.getString("notes"), row.getString("proof_reference"),
        row.getString("submitted_by_subject"), instant(row, "submitted_at"), row.getString("completion_state"),
        instant(row, "completed_at"));
  }
  private static Instant instant(ResultSet row, String column) throws SQLException {
    OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
