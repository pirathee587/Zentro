package com.zentro.identitypartner.user.service;

import com.zentro.identitypartner.user.audit.UserAdminAuditRecord;
import com.zentro.identitypartner.user.audit.UserAdminAuditRecord.Action;
import com.zentro.identitypartner.user.dto.AdminUpdateUserRequest;
import com.zentro.identitypartner.user.dto.UpdateUserProfileRequest;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import com.zentro.identitypartner.user.exception.UserNotFoundException;
import com.zentro.identitypartner.user.repository.UserAdminAuditRepository;
import com.zentro.identitypartner.user.validation.UserInputRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserProfileService {
  private static final String PROFILE_SQL = """
      SELECT u.id, u.keycloak_subject, u.email, u.display_name, u.phone, u.account_status,
             COALESCE(m.status, '') AS merchant_status
        FROM identity.app_user u
        LEFT JOIN identity.merchant_application m ON m.app_user_id = u.id
       WHERE u.keycloak_subject = ?
      """;

  private static final String PROFILE_BY_ID_SQL = """
      SELECT u.id, u.keycloak_subject, u.email, u.display_name, u.phone, u.account_status,
             COALESCE(m.status, '') AS merchant_status
        FROM identity.app_user u
        LEFT JOIN identity.merchant_application m ON m.app_user_id = u.id
       WHERE u.id = ?
      """;

  /** The target row is locked so concurrent dispatcher changes run one after another and each sees the old status. */
  private static final String LOCK_BY_ID_SQL = PROFILE_BY_ID_SQL + " FOR UPDATE OF u";

  private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

  private final JdbcTemplate jdbc;
  private final UserAdminAuditRepository audit;

  public UserProfileService(JdbcTemplate jdbc, UserAdminAuditRepository audit) {
    this.jdbc = jdbc;
    this.audit = audit;
  }

  @Transactional
  public UserProfile current(Jwt jwt) {
    String subject = jwt.getSubject();
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("The access token has no subject.");
    }
    jdbc.update("""
        INSERT INTO identity.app_user (keycloak_subject, email, display_name, phone)
        VALUES (?, ?, ?, ?)
        ON CONFLICT (keycloak_subject) DO UPDATE SET
          email = COALESCE(EXCLUDED.email, identity.app_user.email),
          display_name = COALESCE(NULLIF(EXCLUDED.display_name, ''), identity.app_user.display_name),
          phone = COALESCE(NULLIF(EXCLUDED.phone, ''), identity.app_user.phone),
          updated_at = now()
        """, subject, jwt.getClaimAsString("email"), displayName(jwt), phone(jwt));
    return select(subject, roles(jwt));
  }

  @Transactional
  public UserProfile update(Jwt jwt, UpdateUserProfileRequest request) {
    UserProfile current = current(jwt);
    if (!"ACTIVE".equalsIgnoreCase(current.accountStatus())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is " + current.accountStatus() + ".");
    }
    jdbc.update("""
        UPDATE identity.app_user
           SET display_name = COALESCE(NULLIF(?, ''), display_name),
               phone = COALESCE(NULLIF(?, ''), phone),
               updated_at = now()
         WHERE id = ?
        """, clean(request.name()), clean(request.phone()), current.id());
    return select(jwt.getSubject(), roles(jwt));
  }

  @Transactional(readOnly = true)
  public UserProfile getById(UUID id) {
    try {
      return jdbc.queryForObject(PROFILE_BY_ID_SQL, (rs, rowNum) -> mapRow(rs, List.of()), id);
    } catch (EmptyResultDataAccessException e) {
      throw new UserNotFoundException();
    }
  }

  @Transactional
  public UserProfile getByIdForCaller(Jwt jwt, UUID targetUserId) {
    UserProfile caller = current(jwt);
    boolean isDispatcher = roles(jwt).contains("DISPATCHER");
    boolean isOwner = caller.id().equals(targetUserId);

    if (!isDispatcher && !isOwner) {
      throw new AccessDeniedException("Access denied: cannot view another user's profile.");
    }
    if (isOwner) {
      return select(jwt.getSubject(), roles(jwt));
    }
    return getById(targetUserId);
  }

  @Transactional
  public UserProfile updateByIdForCaller(Jwt jwt, UUID targetUserId, UpdateUserProfileRequest request) {
    UserProfile caller = current(jwt);
    if (!"ACTIVE".equalsIgnoreCase(caller.accountStatus())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is " + caller.accountStatus() + ".");
    }
    boolean isDispatcher = roles(jwt).contains("DISPATCHER");
    boolean isOwner = caller.id().equals(targetUserId);

    if (!isDispatcher && !isOwner) {
      throw new AccessDeniedException("Access denied: cannot update another user's profile.");
    }
    if (isOwner) {
      return update(jwt, request);
    }
    // A dispatcher changing another user's profile: same rules and audit as the admin API (ZD-042, D5).
    if (request.name() == null && request.phone() == null) {
      throw new InvalidUserInputException("request", "EMPTY_UPDATE", "Provide at least one of name or phone.");
    }
    return applyDispatcherChange(caller, jwt.getSubject(), targetUserId, request.name(), request.phone(), null, null);
  }

  /**
   * Users for the dispatcher list. {@code status} must be ACTIVE, DISABLED or SUSPENDED when given (else 400);
   * {@code search} (at most 100 characters) matches name or email literally: % and _ are not wildcards.
   */
  @Transactional(readOnly = true)
  public List<UserProfile> listUsers(String status, String search) {
    String statusFilter = UserInputRules.statusFilter(status);
    String searchText = UserInputRules.search(search);
    StringBuilder sql = new StringBuilder("""
        SELECT u.id, u.keycloak_subject, u.email, u.display_name, u.phone, u.account_status,
               COALESCE(m.status, '') AS merchant_status
          FROM identity.app_user u
          LEFT JOIN identity.merchant_application m ON m.app_user_id = u.id
         WHERE 1=1
        """);
    List<Object> params = new ArrayList<>();
    if (statusFilter != null) {
      sql.append(" AND u.account_status = ?");
      params.add(statusFilter);
    }
    if (searchText != null) {
      sql.append(" AND (LOWER(u.display_name) LIKE ? ESCAPE '\\' OR LOWER(u.email) LIKE ? ESCAPE '\\')");
      String term = UserInputRules.containsPattern(searchText);
      params.add(term);
      params.add(term);
    }
    sql.append(" ORDER BY u.created_at DESC");
    return jdbc.query(sql.toString(), (rs, rowNum) -> mapRow(rs, List.of()), params.toArray());
  }

  /**
   * Dispatcher change to a user's name, phone or account status ({@code PATCH /api/v1/admin/users/{userId}}).
   * <ul>
   *   <li>Only DISPATCHER may call it (checked here as well as on the controller).</li>
   *   <li>Name and phone are validated only when they differ from the stored values, so users with older data stay
   *       editable; status is ACTIVE, DISABLED or SUSPENDED (any case).</li>
   *   <li>A dispatcher cannot change their own account status; sending the current status is a no-op.</li>
   *   <li>A request that changes nothing writes nothing and returns the user unchanged.</li>
   *   <li>Every real change writes one audit row in the same transaction.</li>
   * </ul>
   */
  @Transactional
  public UserProfile adminUpdate(UUID targetUserId, Jwt actor, AdminUpdateUserRequest request) {
    requireDispatcher(actor);
    if (request == null || !request.hasUpdatableField()) {
      throw new InvalidUserInputException("request", "EMPTY_UPDATE",
          "Provide at least one of name, phone or accountStatus.");
    }
    UserProfile actorUser = current(actor);
    if (actorUser.id().equals(targetUserId) && request.accountStatus() != null) {
      rejectOwnStatusChange(UserInputRules.accountStatus(request.accountStatus(), "accountStatus"),
          actorUser.accountStatus());
    }
    if (!"ACTIVE".equalsIgnoreCase(actorUser.accountStatus())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is " + actorUser.accountStatus() + ".");
    }
    return applyDispatcherChange(actorUser, actor.getSubject(), targetUserId, request.name(), request.phone(),
        request.accountStatus(), request.reason());
  }

  private UserProfile applyDispatcherChange(UserProfile actorUser, String actorSubject, UUID targetUserId,
      String requestedName, String requestedPhone, String rawStatus, String rawReason) {
    UserProfile before = lockById(targetUserId);
    String requestedStatus = rawStatus == null ? null : UserInputRules.accountStatus(rawStatus, "accountStatus");
    String reason = UserInputRules.reason(rawReason);
    List<String> changed = new ArrayList<>();

    String name = before.name();
    if (requestedName != null && !requestedName.strip().equals(before.name())) {
      name = UserInputRules.name(requestedName);
      if (!name.equals(before.name())) {
        changed.add("name");
      }
    }
    String phone = before.phone();
    if (requestedPhone != null && !requestedPhone.strip().equals(before.phone())) {
      phone = UserInputRules.phone(requestedPhone);
      if (!phone.equals(before.phone())) {
        changed.add("phone");
      }
    }
    String oldStatus = before.accountStatus();
    String status = oldStatus;
    if (requestedStatus != null && !requestedStatus.equals(oldStatus)) {
      if (actorUser.id().equals(targetUserId)) {
        rejectOwnStatusChange(requestedStatus, oldStatus);
      }
      status = requestedStatus;
      changed.add("accountStatus");
    }

    if (changed.isEmpty()) {
      log.debug("Dispatcher user update changed nothing: actorUserId={} targetUserId={}", actorUser.id(), targetUserId);
      return before;
    }

    jdbc.update("""
        UPDATE identity.app_user
           SET display_name = ?,
               phone = ?,
               account_status = ?,
               updated_at = now()
         WHERE id = ?
        """, name, phone, status, targetUserId);

    Action action = changed.contains("accountStatus") ? Action.STATUS_CHANGED : Action.PROFILE_UPDATED;
    audit.insert(new UserAdminAuditRecord(actorUser.id(), actorSubject, targetUserId, action, changed, oldStatus,
        status, reason));
    log.info("User changed by dispatcher: actorUserId={} targetUserId={} action={} changedFields={} oldStatus={}"
        + " newStatus={}", actorUser.id(), targetUserId, action, changed, oldStatus, status);
    return getById(targetUserId);
  }

  private UserProfile lockById(UUID id) {
    try {
      return jdbc.queryForObject(LOCK_BY_ID_SQL, (rs, rowNum) -> mapRow(rs, List.of()), id);
    } catch (EmptyResultDataAccessException e) {
      throw new UserNotFoundException();
    }
  }

  /** A dispatcher cannot change their own account status; asking for the current status is allowed (no-op). */
  private static void rejectOwnStatusChange(String requestedStatus, String currentStatus) {
    if (requestedStatus.equals(currentStatus)) {
      return;
    }
    String message = "ACTIVE".equals(requestedStatus)
        ? "Dispatchers cannot change their own account status."
        : "Administrators cannot disable or suspend their own account.";
    throw new InvalidUserInputException("accountStatus", "SELF_STATUS_CHANGE", message);
  }

  private static void requireDispatcher(Jwt actor) {
    if (actor == null || !roles(actor).contains("DISPATCHER")) {
      throw new AccessDeniedException("Only dispatchers can manage users.");
    }
  }

  private UserProfile select(String subject, List<String> roles) {
    try {
      return jdbc.queryForObject(PROFILE_SQL, (rs, rowNum) -> mapRow(rs, roles), subject);
    } catch (EmptyResultDataAccessException e) {
      throw new UserNotFoundException();
    }
  }

  private UserProfile mapRow(ResultSet rs, List<String> roles) throws SQLException {
    String merchantStatus = rs.getString("merchant_status");
    String status = rs.getString("account_status");
    if (status == null || status.isBlank()) status = "ACTIVE";
    boolean enabled = "ACTIVE".equalsIgnoreCase(status);
    return new UserProfile(
        rs.getObject("id", UUID.class),
        rs.getString("email"),
        rs.getString("display_name"),
        rs.getString("phone"),
        enabled,
        status,
        merchantStatus == null || merchantStatus.isBlank() ? null : merchantStatus,
        roles);
  }

  private static String displayName(Jwt jwt) {
    String name = jwt.getClaimAsString("name");
    if (name != null && !name.isBlank()) return name.trim();
    String given = jwt.getClaimAsString("given_name");
    String family = jwt.getClaimAsString("family_name");
    String combined = String.join(" ", List.of(given == null ? "" : given.trim(), family == null ? "" : family.trim())).trim();
    return combined;
  }

  private static String phone(Jwt jwt) {
    String phone = jwt.getClaimAsString("phone_number");
    if (phone == null || phone.isBlank()) phone = jwt.getClaimAsString("phone");
    return clean(phone);
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public static List<String> roles(Jwt jwt) {
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (!(realmAccess instanceof Map<?, ?> claims) || !(claims.get("roles") instanceof List<?> values)) return List.of();
    return values.stream().filter(String.class::isInstance).map(String.class::cast)
        .filter(role -> List.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER").contains(role)).toList();
  }
}
