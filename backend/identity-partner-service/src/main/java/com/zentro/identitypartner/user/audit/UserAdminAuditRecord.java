package com.zentro.identitypartner.user.audit;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One dispatcher change to a user, as stored in {@code identity.user_admin_audit}. Holds field names only, never the
 * old or new names, phone numbers or emails.
 *
 * @param actorSubject  Keycloak subject of the dispatcher; stored for traceability, never returned to clients
 * @param changedFields names of the fields that really changed, for example {@code ["name", "accountStatus"]}
 * @param oldStatus     account status before the change (always recorded)
 * @param newStatus     account status after the change (always recorded)
 * @param reason        optional reason given by the dispatcher (at most 500 characters)
 */
public record UserAdminAuditRecord(
    UUID actorUserId,
    String actorSubject,
    UUID targetUserId,
    Action action,
    List<String> changedFields,
    String oldStatus,
    String newStatus,
    String reason) {

  /** STATUS_CHANGED when the account status changed (with or without other fields), otherwise PROFILE_UPDATED. */
  public enum Action {
    PROFILE_UPDATED,
    STATUS_CHANGED
  }

  public UserAdminAuditRecord {
    Objects.requireNonNull(actorUserId, "actorUserId");
    Objects.requireNonNull(actorSubject, "actorSubject");
    Objects.requireNonNull(targetUserId, "targetUserId");
    Objects.requireNonNull(action, "action");
    changedFields = List.copyOf(changedFields);
    if (changedFields.isEmpty()) {
      throw new IllegalArgumentException("An audit record needs at least one changed field");
    }
  }

  /** Names and ids only, so the record can be logged safely. */
  @Override
  public String toString() {
    return "UserAdminAuditRecord[actorUserId=" + actorUserId + ", targetUserId=" + targetUserId + ", action="
        + action + ", changedFields=" + changedFields + ", oldStatus=" + oldStatus + ", newStatus=" + newStatus
        + ", reasonGiven=" + (reason != null) + "]";
  }
}
