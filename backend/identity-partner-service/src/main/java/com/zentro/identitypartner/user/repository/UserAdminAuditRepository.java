package com.zentro.identitypartner.user.repository;

import com.zentro.identitypartner.user.audit.UserAdminAuditRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code identity.user_admin_audit}. Every call must join the transaction of the user change it describes,
 * so the change and its audit row are committed or rolled back together. The table is append-only (a database
 * trigger rejects UPDATE, DELETE and TRUNCATE), so this repository only inserts.
 */
@Repository
public class UserAdminAuditRepository {
  private final JdbcTemplate jdbc;

  public UserAdminAuditRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(UserAdminAuditRecord record) {
    jdbc.update("""
        INSERT INTO identity.user_admin_audit
            (actor_user_id, actor_subject, target_user_id, action, changed_fields, old_status, new_status, reason)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        record.actorUserId(),
        record.actorSubject(),
        record.targetUserId(),
        record.action().name(),
        record.changedFields().toArray(String[]::new),
        record.oldStatus(),
        record.newStatus(),
        record.reason());
  }
}
