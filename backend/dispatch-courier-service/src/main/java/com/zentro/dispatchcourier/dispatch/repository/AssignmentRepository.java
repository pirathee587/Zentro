package com.zentro.dispatchcourier.dispatch.repository;

import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.exception.DispatchException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AssignmentRepository {
  public record Assignment(UUID parcelId, UUID courierId, UUID courierUserId,
                           String assignedBy, Instant assignedAt) {}
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;
  private final ObjectMapper objectMapper;

  public AssignmentRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
    this(jdbc, manager, new ObjectMapper().findAndRegisterModules());
  }

  @Autowired
  public AssignmentRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
      ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
    this.objectMapper = objectMapper;
  }

  public boolean exists(UUID parcelId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM dispatch.delivery_assignment WHERE parcel_id = ?)", Boolean.class, parcelId));
  }

  public java.util.Optional<Assignment> find(UUID parcelId) {
    return jdbc.query("SELECT * FROM dispatch.delivery_assignment WHERE parcel_id = ?",
        (row, index) -> new Assignment(row.getObject("parcel_id", UUID.class),
            row.getObject("courier_profile_id", UUID.class), row.getObject("courier_user_id", UUID.class),
            row.getString("assigned_by_subject"), row.getObject("assigned_at", OffsetDateTime.class).toInstant()),
        parcelId).stream().findFirst();
  }

  public Set<UUID> assignedIds(Collection<UUID> candidates) {
    Set<UUID> result = new HashSet<>();
    List<UUID> ids = List.copyOf(candidates);
    var named = new NamedParameterJdbcTemplate(jdbc);
    for (int offset = 0; offset < ids.size(); offset += 500) {
      result.addAll(named.query("SELECT parcel_id FROM dispatch.delivery_assignment WHERE parcel_id IN (:ids)",
          Map.of("ids", ids.subList(offset, Math.min(offset + 500, ids.size()))),
          (row, index) -> row.getObject("parcel_id", UUID.class)));
    }
    return result;
  }

  public Map<UUID, Long> assignmentCounts() {
    return jdbc.query("""
        SELECT courier_profile_id, COUNT(*) AS assignment_count
          FROM dispatch.delivery_assignment
         GROUP BY courier_profile_id
        """, (row, index) -> Map.entry(
        row.getObject("courier_profile_id", UUID.class), row.getLong("assignment_count")))
        .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  /** Unique parcel key is the arbiter even when two independent requests race. */
  public Assignment insert(UUID parcelId, IdentityCourierClient.Courier courier, String subject) {
    return transactions.execute(status -> {
      jdbc.query(
          "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
          preparedStatement -> preparedStatement.setString(1, parcelId.toString()),
          resultSet -> null);
      List<Assignment> inserted = jdbc.query("""
          INSERT INTO dispatch.delivery_assignment
            (parcel_id, courier_profile_id, courier_user_id, assigned_by_subject)
          VALUES (?, ?, ?, ?) ON CONFLICT (parcel_id) DO NOTHING
          RETURNING parcel_id, courier_profile_id, courier_user_id, assigned_by_subject, assigned_at
          """, (row, index) -> new Assignment(row.getObject("parcel_id", UUID.class),
              row.getObject("courier_profile_id", UUID.class), row.getObject("courier_user_id", UUID.class),
              row.getString("assigned_by_subject"), row.getObject("assigned_at", OffsetDateTime.class).toInstant()),
          parcelId, courier.id(), courier.userId(), subject);
      if (inserted.isEmpty()) throw DispatchException.assigned();
      Assignment assignment = inserted.get(0);
      writeAuditAndEvent(assignment.parcelId(), "ASSIGNED", null, assignment.courierId(), subject);
      return assignment;
    });
  }

  public Assignment reassign(UUID parcelId, IdentityCourierClient.Courier courier, String subject) {
    return transactions.execute(status -> {
      lockParcel(parcelId);
      Assignment current = findForUpdate(parcelId);
      if (current == null) throw notAssigned();
      if (current.courierId().equals(courier.id())) {
        throw new DispatchException(org.springframework.http.HttpStatus.CONFLICT,
            "COURIER_ALREADY_ASSIGNED", "This courier is already assigned to the delivery.");
      }
      jdbc.update("""
          UPDATE dispatch.delivery_assignment
             SET courier_profile_id = ?, courier_user_id = ?, assigned_by_subject = ?, assigned_at = now()
           WHERE parcel_id = ?
          """, courier.id(), courier.userId(), subject, parcelId);
      Assignment updated = findForUpdate(parcelId);
      writeAuditAndEvent(parcelId, "REASSIGNED", current.courierId(), updated.courierId(), subject);
      return updated;
    });
  }

  public void cancel(UUID parcelId, String subject) {
    transactions.executeWithoutResult(status -> {
      lockParcel(parcelId);
      Assignment current = findForUpdate(parcelId);
      if (current == null) throw notAssigned();
      jdbc.update("DELETE FROM dispatch.delivery_assignment WHERE parcel_id = ?", parcelId);
      writeAuditAndEvent(parcelId, "CANCELLED", current.courierId(), null, subject);
    });
  }

  private void lockParcel(UUID parcelId) {
    jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
        preparedStatement -> preparedStatement.setString(1, parcelId.toString()),
        resultSet -> null);
  }

  private Assignment findForUpdate(UUID parcelId) {
    return jdbc.query("""
        SELECT parcel_id, courier_profile_id, courier_user_id, assigned_by_subject, assigned_at
          FROM dispatch.delivery_assignment WHERE parcel_id = ? FOR UPDATE
        """, (row, index) -> new Assignment(row.getObject("parcel_id", UUID.class),
            row.getObject("courier_profile_id", UUID.class), row.getObject("courier_user_id", UUID.class),
            row.getString("assigned_by_subject"), row.getObject("assigned_at", OffsetDateTime.class).toInstant()),
        parcelId).stream().findFirst().orElse(null);
  }

  private void writeAuditAndEvent(UUID parcelId, String action, UUID oldCourier, UUID newCourier, String subject) {
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update("""
        INSERT INTO dispatch.assignment_audit
          (audit_id, parcel_id, action, old_courier_profile_id, new_courier_profile_id, actor_subject, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), parcelId, action, oldCourier, newCourier, subject, occurredAt);
    UUID eventId = UUID.randomUUID();
    String eventType = switch (action) {
      case "ASSIGNED" -> "delivery.assigned";
      case "REASSIGNED" -> "delivery.reassigned";
      case "CANCELLED" -> "delivery.cancelled";
      default -> throw new IllegalArgumentException("Unsupported assignment action");
    };
    try {
      java.util.Map<String, Object> eventPayload = new java.util.LinkedHashMap<>();
      eventPayload.put("action", action);
      eventPayload.put("oldCourierId", oldCourier);
      eventPayload.put("newCourierId", newCourier);
      eventPayload.put("actorSubject", subject);
      String payload = objectMapper.writeValueAsString(java.util.Map.of(
          "eventId", eventId,
          "eventType", eventType,
          "occurredAt", occurredAt.toInstant(),
          "aggregateId", parcelId,
          "payload", eventPayload));
      jdbc.update("""
          INSERT INTO dispatch.outbox_event
            (event_id, aggregate_id, event_type, payload, occurred_at)
          VALUES (?, ?, ?, ?::jsonb, ?)
          """, eventId, parcelId, eventType, payload, occurredAt);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("Could not serialize assignment event", error);
    }
  }

  private static DispatchException notAssigned() {
    return new DispatchException(org.springframework.http.HttpStatus.NOT_FOUND,
        "DELIVERY_NOT_ASSIGNED", "The delivery has no active assignment.");
  }
}
