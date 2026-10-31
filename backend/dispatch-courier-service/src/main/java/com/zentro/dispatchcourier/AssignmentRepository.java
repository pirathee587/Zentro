package com.zentro.dispatchcourier;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AssignmentRepository {
  public record Assignment(UUID parcelId, UUID courierId, UUID courierUserId,
                           String assignedBy, Instant assignedAt) {}
  private final JdbcTemplate jdbc;
  private final TransactionTemplate transactions;

  public AssignmentRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(manager);
  }

  public boolean exists(UUID parcelId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM dispatch.delivery_assignment WHERE parcel_id = ?)", Boolean.class, parcelId));
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

  /** Unique parcel key is the arbiter even when two independent requests race. */
  public Assignment insert(UUID parcelId, IdentityCourierClient.Courier courier, String subject) {
    return transactions.execute(status -> {
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
      return inserted.get(0);
    });
  }
}
