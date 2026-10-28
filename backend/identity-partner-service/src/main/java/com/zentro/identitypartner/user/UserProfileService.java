package com.zentro.identitypartner.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

  private final JdbcTemplate jdbc;

  public UserProfileService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
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
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with id: " + id);
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
    boolean isDispatcher = roles(jwt).contains("DISPATCHER");
    boolean isOwner = caller.id().equals(targetUserId);

    if (!isDispatcher && !isOwner) {
      throw new AccessDeniedException("Access denied: cannot update another user's profile.");
    }
    if (isOwner) {
      return update(jwt, request);
    }
    // Dispatcher updating user's profile
    getById(targetUserId); // ensures exists
    jdbc.update("""
        UPDATE identity.app_user
           SET display_name = COALESCE(NULLIF(?, ''), display_name),
               phone = COALESCE(NULLIF(?, ''), phone),
               updated_at = now()
         WHERE id = ?
        """, clean(request.name()), clean(request.phone()), targetUserId);
    return getById(targetUserId);
  }

  @Transactional(readOnly = true)
  public List<UserProfile> listUsers(String status, String search) {
    StringBuilder sql = new StringBuilder("""
        SELECT u.id, u.keycloak_subject, u.email, u.display_name, u.phone, u.account_status,
               COALESCE(m.status, '') AS merchant_status
          FROM identity.app_user u
          LEFT JOIN identity.merchant_application m ON m.app_user_id = u.id
         WHERE 1=1
        """);
    List<Object> params = new ArrayList<>();
    if (status != null && !status.isBlank()) {
      sql.append(" AND u.account_status = ?");
      params.add(status.trim().toUpperCase());
    }
    if (search != null && !search.isBlank()) {
      sql.append(" AND (LOWER(u.display_name) LIKE ? OR LOWER(u.email) LIKE ?)");
      String term = "%" + search.trim().toLowerCase() + "%";
      params.add(term);
      params.add(term);
    }
    sql.append(" ORDER BY u.created_at DESC");
    return jdbc.query(sql.toString(), (rs, rowNum) -> mapRow(rs, List.of()), params.toArray());
  }

  @Transactional
  public UserProfile adminUpdate(UUID targetUserId, Jwt actor, AdminUpdateUserRequest request) {
    UserProfile actorUser = current(actor);

    // Self-lockout check: an admin cannot disable or suspend their own account
    if (actorUser.id().equals(targetUserId) && request.accountStatus() != null) {
      String requestedStatus = request.accountStatus().trim().toUpperCase();
      if (!"ACTIVE".equals(requestedStatus)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Administrators cannot disable or suspend their own account.");
      }
    }

    // Verify target user exists
    getById(targetUserId);

    String status = clean(request.accountStatus());
    if (status != null) {
      status = status.toUpperCase();
      if (!List.of("ACTIVE", "DISABLED", "SUSPENDED").contains(status)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid account status: " + request.accountStatus());
      }
    }

    jdbc.update("""
        UPDATE identity.app_user
           SET display_name = COALESCE(NULLIF(?, ''), display_name),
               phone = COALESCE(NULLIF(?, ''), phone),
               account_status = COALESCE(NULLIF(?, ''), account_status),
               updated_at = now()
         WHERE id = ?
        """, clean(request.name()), clean(request.phone()), status, targetUserId);

    return getById(targetUserId);
  }

  private UserProfile select(String subject, List<String> roles) {
    try {
      return jdbc.queryForObject(PROFILE_SQL, (rs, rowNum) -> mapRow(rs, roles), subject);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found with subject: " + subject);
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
        merchantStatus.isBlank() ? null : merchantStatus,
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
