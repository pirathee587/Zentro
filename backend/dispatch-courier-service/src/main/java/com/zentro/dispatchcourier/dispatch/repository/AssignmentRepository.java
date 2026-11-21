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
  public record Assignment(
      UUID parcelId,
      UUID courierId,
      UUID courierUserId,
      String assignedBy,
      Instant assignedAt,
      String assignmentStatus,
      Instant respondedAt,
      String rejectionReason,
      String courierName,
      String courierPhone,
      String courierVehicle,
      String courierZone) {
    public Assignment(UUID parcelId, UUID courierId, UUID courierUserId, String assignedBy, Instant assignedAt) {
      this(parcelId, courierId, courierUserId, assignedBy, assignedAt,
          "PENDING_ACCEPTANCE", null, null, null, null, null, null);
    }
  }
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

  private static Assignment mapAssignment(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    OffsetDateTime responded = row.getObject("responded_at", OffsetDateTime.class);
    return new Assignment(
        row.getObject("parcel_id", UUID.class),
        row.getObject("courier_profile_id", UUID.class),
        row.getObject("courier_user_id", UUID.class),
        row.getString("assigned_by_subject"),
        row.getObject("assigned_at", OffsetDateTime.class).toInstant(),
        row.getString("assignment_status"),
        responded != null ? responded.toInstant() : null,
        row.getString("rejection_reason"),
        row.getString("courier_name"),
        row.getString("courier_phone"),
        row.getString("courier_vehicle"),
        row.getString("courier_zone"));
  }

  public boolean exists(UUID parcelId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM dispatch.delivery_assignment WHERE parcel_id = ? AND assignment_status <> 'REJECTED')",
        Boolean.class, parcelId));
  }

  public java.util.Optional<Assignment> find(UUID parcelId) {
    return jdbc.query("SELECT * FROM dispatch.delivery_assignment WHERE parcel_id = ?",
        AssignmentRepository::mapAssignment, parcelId).stream().findFirst();
  }

  public List<Assignment> findAll() {
    return jdbc.query("SELECT * FROM dispatch.delivery_assignment ORDER BY assigned_at DESC",
        AssignmentRepository::mapAssignment);
  }

  public Set<UUID> assignedIds(Collection<UUID> candidates) {
    Set<UUID> result = new HashSet<>();
    List<UUID> ids = List.copyOf(candidates);
    var named = new NamedParameterJdbcTemplate(jdbc);
    for (int offset = 0; offset < ids.size(); offset += 500) {
      result.addAll(named.query(
          "SELECT parcel_id FROM dispatch.delivery_assignment WHERE parcel_id IN (:ids) AND assignment_status <> 'REJECTED'",
          Map.of("ids", ids.subList(offset, Math.min(offset + 500, ids.size()))),
          (row, index) -> row.getObject("parcel_id", UUID.class)));
    }
    return result;
  }

  public Map<UUID, Long> assignmentCounts() {
    return jdbc.query("""
        SELECT courier_profile_id, COUNT(*) AS assignment_count
          FROM dispatch.delivery_assignment
         WHERE assignment_status <> 'REJECTED'
         GROUP BY courier_profile_id
        """, (row, index) -> Map.entry(
        row.getObject("courier_profile_id", UUID.class), row.getLong("assignment_count")))
        .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public long getAssignmentCount(UUID courierProfileId) {
    if (courierProfileId == null) return 0L;
    Long count = jdbc.queryForObject(
        "SELECT COUNT(*) FROM dispatch.delivery_assignment WHERE courier_profile_id = ? AND assignment_status <> 'REJECTED'",
        Long.class, courierProfileId);
    return count != null ? count : 0L;
  }

  /** Unique parcel key is the arbiter even when two independent requests race. */
  public Assignment insert(UUID parcelId, IdentityCourierClient.Courier courier, String subject) {
    return transactions.execute(status -> {
      lockParcel(parcelId);
      Assignment existing = findForUpdate(parcelId);
      if (existing != null) {
        if (!"REJECTED".equals(existing.assignmentStatus())) {
          throw DispatchException.assigned();
        }
        jdbc.update("""
            UPDATE dispatch.delivery_assignment
               SET courier_profile_id = ?, courier_user_id = ?, assigned_by_subject = ?, assigned_at = now(),
                   assignment_status = 'PENDING_ACCEPTANCE', responded_at = NULL, rejection_reason = NULL,
                   courier_name = ?, courier_phone = ?, courier_vehicle = ?, courier_zone = ?
             WHERE parcel_id = ?
            """, courier.id(), courier.userId(), subject,
            courier.name(), courier.phone(), courier.vehicleType(), courier.assignedZone(), parcelId);
        Assignment updated = findForUpdate(parcelId);
        writeAuditAndEvent(parcelId, "REASSIGNED", existing.courierId(), updated.courierId(), subject);
        return updated;
      }
      List<Assignment> inserted = jdbc.query("""
          INSERT INTO dispatch.delivery_assignment
            (parcel_id, courier_profile_id, courier_user_id, assigned_by_subject,
             assignment_status, courier_name, courier_phone, courier_vehicle, courier_zone)
          VALUES (?, ?, ?, ?, 'PENDING_ACCEPTANCE', ?, ?, ?, ?)
          RETURNING parcel_id, courier_profile_id, courier_user_id, assigned_by_subject, assigned_at,
                    assignment_status, responded_at, rejection_reason,
                    courier_name, courier_phone, courier_vehicle, courier_zone
          """, AssignmentRepository::mapAssignment,
          parcelId, courier.id(), courier.userId(), subject,
          courier.name(), courier.phone(), courier.vehicleType(), courier.assignedZone());
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
      if (current.courierId().equals(courier.id()) && !"REJECTED".equals(current.assignmentStatus())) {
        throw new DispatchException(org.springframework.http.HttpStatus.CONFLICT,
            "COURIER_ALREADY_ASSIGNED", "This courier is already assigned to the delivery.");
      }
      jdbc.update("""
          UPDATE dispatch.delivery_assignment
             SET courier_profile_id = ?, courier_user_id = ?, assigned_by_subject = ?, assigned_at = now(),
                 assignment_status = 'PENDING_ACCEPTANCE', responded_at = NULL, rejection_reason = NULL,
                 courier_name = ?, courier_phone = ?, courier_vehicle = ?, courier_zone = ?
           WHERE parcel_id = ?
          """, courier.id(), courier.userId(), subject,
          courier.name(), courier.phone(), courier.vehicleType(), courier.assignedZone(), parcelId);
      Assignment updated = findForUpdate(parcelId);
      writeAuditAndEvent(parcelId, "REASSIGNED", current.courierId(), updated.courierId(), subject);
      return updated;
    });
  }

  public Assignment accept(UUID parcelId, UUID courierProfileId, UUID courierUserId, String subject) {
    return transactions.execute(status -> {
      lockParcel(parcelId);
      Assignment current = findForUpdate(parcelId);
      if (current == null) throw notAssigned();
      if (!current.courierId().equals(courierProfileId) || !current.courierUserId().equals(courierUserId)) {
        throw new DispatchException(org.springframework.http.HttpStatus.FORBIDDEN,
            "DELIVERY_FORBIDDEN", "This delivery is not assigned to you.");
      }
      if ("ACCEPTED".equals(current.assignmentStatus())) {
        return current;
      }
      if (!"PENDING_ACCEPTANCE".equals(current.assignmentStatus())) {
        throw new DispatchException(org.springframework.http.HttpStatus.CONFLICT,
            "INVALID_ASSIGNMENT_STATE", "Only a pending assignment can be accepted.");
      }
      jdbc.update("""
          UPDATE dispatch.delivery_assignment
             SET assignment_status = 'ACCEPTED', responded_at = now(), rejection_reason = NULL
           WHERE parcel_id = ?
          """, parcelId);
      Assignment updated = findForUpdate(parcelId);
      writeAuditAndEvent(parcelId, "ACCEPTED", current.courierId(), current.courierId(), subject);
      return updated;
    });
  }

  public Assignment reject(UUID parcelId, UUID courierProfileId, UUID courierUserId, String reason, String subject) {
    String cleanReason = reason == null ? "" : reason.trim();
    if (cleanReason.isEmpty() || cleanReason.length() > 500) {
      throw new DispatchException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
          "INVALID_REJECTION_REASON", "A rejection reason (1-500 characters) is required.");
    }
    return transactions.execute(status -> {
      lockParcel(parcelId);
      Assignment current = findForUpdate(parcelId);
      if (current == null) throw notAssigned();
      if (!current.courierId().equals(courierProfileId) || !current.courierUserId().equals(courierUserId)) {
        throw new DispatchException(org.springframework.http.HttpStatus.FORBIDDEN,
            "DELIVERY_FORBIDDEN", "This delivery is not assigned to you.");
      }
      if ("REJECTED".equals(current.assignmentStatus())) {
        return current;
      }
      if (!"PENDING_ACCEPTANCE".equals(current.assignmentStatus())) {
        throw new DispatchException(org.springframework.http.HttpStatus.CONFLICT,
            "INVALID_ASSIGNMENT_STATE", "Only a pending assignment can be rejected.");
      }
      jdbc.update("""
          UPDATE dispatch.delivery_assignment
             SET assignment_status = 'REJECTED', responded_at = now(), rejection_reason = ?
           WHERE parcel_id = ?
          """, cleanReason, parcelId);
      Assignment updated = findForUpdate(parcelId);
      writeAuditAndEvent(parcelId, "REJECTED", current.courierId(), null, subject);
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
        SELECT parcel_id, courier_profile_id, courier_user_id, assigned_by_subject, assigned_at,
               assignment_status, responded_at, rejection_reason,
               courier_name, courier_phone, courier_vehicle, courier_zone
          FROM dispatch.delivery_assignment WHERE parcel_id = ? FOR UPDATE
        """, AssignmentRepository::mapAssignment,
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
      case "ACCEPTED" -> "delivery.accepted";
      case "REJECTED" -> "delivery.rejected";
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
