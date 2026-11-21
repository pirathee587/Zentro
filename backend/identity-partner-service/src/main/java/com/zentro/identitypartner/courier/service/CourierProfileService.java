package com.zentro.identitypartner.courier.service;

import com.zentro.identitypartner.courier.dto.CourierProfile;


import com.zentro.identitypartner.courier.dto.CourierGrpcResponse;
import com.zentro.identitypartner.courier.dto.UpdateCourierProfileRequest;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.service.UserProfileService;
import com.zentro.identitypartner.user.validation.UserInputRules;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CourierProfileService {
  private static final String PROFILE_SELECT = """
      SELECT cp.id AS profile_id,
             u.id AS user_id,
             u.email,
             u.display_name,
             u.phone,
             u.account_status,
             cp.is_active,
             cp.assigned_zone,
             cp.vehicle_type,
             cp.created_at,
             cp.updated_at
        FROM identity.app_user u
        INNER JOIN identity.courier_profile cp ON cp.app_user_id = u.id
      """;

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(CourierProfileService.class);

  private final JdbcTemplate jdbc;
  private final UserProfileService profiles;
  private final com.zentro.identitypartner.user.service.KeycloakAdminService keycloakAdmin;
  private final com.zentro.identitypartner.email.EmailSenderService emailSender;

  public CourierProfileService(JdbcTemplate jdbc, UserProfileService profiles) {
    this(jdbc, profiles, null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public CourierProfileService(
      JdbcTemplate jdbc,
      UserProfileService profiles,
      com.zentro.identitypartner.user.service.KeycloakAdminService keycloakAdmin,
      com.zentro.identitypartner.email.EmailSenderService emailSender) {
    this.jdbc = jdbc;
    this.profiles = profiles;
    this.keycloakAdmin = keycloakAdmin;
    this.emailSender = emailSender;
  }

  /**
   * Resolves or initializes the courier profile for the calling courier (from their JWT).
   * Verifies the user has the COURIER role before provisioning the courier profile record.
   */
  @Transactional
  public CourierProfile getByJwt(Jwt jwt) {
    verifyCourierRole(jwt);
    UserProfile user = profiles.current(jwt);
    return getOrCreateProfile(user.id());
  }

  /**
   * Explicitly provisions a courier_profile record for an authenticated user with ROLE_COURIER.
   * Throws 403 Forbidden if the caller is not a COURIER.
   * Reuses the existing user profile in identity.app_user and guarantees idempotency.
   */
  @Transactional
  public CourierProfile provisionCourier(Jwt jwt) {
    verifyCourierRole(jwt);
    UserProfile user = profiles.current(jwt);
    if (user != null && !"ACTIVE".equalsIgnoreCase(user.accountStatus())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Account is " + user.accountStatus() + ".");
    }
    return getOrCreateProfile(user.id());
  }

  private void verifyCourierRole(Jwt jwt) {
    List<String> userRoles = UserProfileService.roles(jwt);
    if (!userRoles.contains("COURIER")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated user does not have COURIER role");
    }
  }

  /**
   * Retrieves an existing courier profile by either courier profile ID or underlying app_user ID.
   * Throws 404 NOT FOUND if no registered courier profile exists.
   */
  @Transactional(readOnly = true)
  public CourierProfile getByCourierId(UUID courierId) {
    if (courierId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Courier ID cannot be null");
    }
    try {
      return jdbc.queryForObject(
          PROFILE_SELECT + " WHERE u.id = ? OR cp.id = ?",
          (rs, rowNum) -> mapRow(rs),
          courierId, courierId);
    } catch (EmptyResultDataAccessException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier profile not found: " + courierId);
    }
  }

  @Transactional
  public CourierProfile getOrCreateProfile(UUID userId) {
    UserProfile user = profiles.getById(userId);

    jdbc.update("""
        INSERT INTO identity.courier_profile (app_user_id, is_active)
        VALUES (?, true)
        ON CONFLICT (app_user_id) DO NOTHING
        """, user.id());

    return getByCourierId(user.id());
  }

  /**
   * Lists only users who have a valid, registered courier_profile record.
   * Customers and merchants without a courier profile are strictly excluded via INNER JOIN.
   */
  @Transactional(readOnly = true)
  public List<CourierProfile> listCouriers(String status, Boolean active, String zone, String search) {
    StringBuilder sql = new StringBuilder("""
        SELECT cp.id AS profile_id,
               u.id AS user_id,
               u.email,
               u.display_name,
               u.phone,
               u.account_status,
               cp.is_active,
               cp.assigned_zone,
               cp.vehicle_type,
               cp.created_at,
               cp.updated_at
          FROM identity.app_user u
          INNER JOIN identity.courier_profile cp ON cp.app_user_id = u.id
         WHERE 1=1
        """);

    List<Object> params = new ArrayList<>();

    String statusFilter = UserInputRules.statusFilter(status);
    if (statusFilter != null) {
      sql.append(" AND u.account_status = ?");
      params.add(statusFilter);
    }
    if (active != null) {
      if (active) {
        sql.append(" AND cp.is_active = true AND u.account_status = 'ACTIVE'");
      } else {
        sql.append(" AND (cp.is_active = false OR u.account_status <> 'ACTIVE')");
      }
    }
    if (zone != null && !zone.isBlank()) {
      sql.append(" AND LOWER(cp.assigned_zone) = LOWER(?)");
      params.add(zone.trim());
    }
    if (search != null && !search.isBlank()) {
      sql.append(" AND (LOWER(u.display_name) LIKE ? OR LOWER(u.email) LIKE ? OR LOWER(u.phone) LIKE ?)");
      String term = "%" + search.trim().toLowerCase() + "%";
      params.add(term);
      params.add(term);
      params.add(term);
    }

    sql.append(" ORDER BY cp.created_at DESC");

    return jdbc.query(sql.toString(), (rs, rowNum) -> mapRow(rs), params.toArray());
  }

  /**
   * Updates an existing registered courier profile.
   * Requires that the target courier already has a registered courier profile;
   * does not create courier profiles for arbitrary customer/merchant users.
   */
  @Transactional
  public CourierProfile updateCourierProfile(UUID courierId, UpdateCourierProfileRequest request) {
    CourierProfile existing = getByCourierId(courierId); // Throws 404 if no registered courier profile exists
    UUID userId = existing.userId();

    jdbc.update("""
        UPDATE identity.courier_profile
           SET is_active = COALESCE(?, is_active),
               assigned_zone = COALESCE(?, assigned_zone),
               vehicle_type = COALESCE(?, vehicle_type),
               updated_at = now()
         WHERE app_user_id = ?
        """,
        request.active(),
        clean(request.assignedZone()),
        clean(request.vehicleType()),
        userId);

    if (request.name() != null || request.phone() != null) {
      jdbc.update("""
          UPDATE identity.app_user
             SET display_name = COALESCE(NULLIF(?, ''), display_name),
                 phone = COALESCE(NULLIF(?, ''), phone),
                 updated_at = now()
           WHERE id = ?
          """,
          clean(request.name()),
          clean(request.phone()),
          userId);
    }

    return getByCourierId(courierId);
  }

  /**
   * S1-side logic for the shared identity_partner.proto GetCourierProfile RPC.
   * S3 calls this to verify courier eligibility before assigning deliveries.
   */
  @Transactional(readOnly = true)
  public CourierGrpcResponse getCourierProfileForGrpc(String courierIdStr) {
    if (courierIdStr == null || courierIdStr.isBlank()) {
      return new CourierGrpcResponse(courierIdStr, false);
    }
    try {
      UUID courierId = UUID.fromString(courierIdStr.trim());
      boolean active = isCourierActive(courierId);
      return new CourierGrpcResponse(courierId.toString(), active);
    } catch (IllegalArgumentException e) {
      return new CourierGrpcResponse(courierIdStr, false);
    }
  }

  /**
   * Courier eligibility rule: courier must have a courier_profile record with is_active = true
   * AND the user account in app_user must have account_status = 'ACTIVE'.
   */
  @Transactional(readOnly = true)
  public boolean isCourierActive(UUID courierId) {
    try {
      CourierProfile profile = getByCourierId(courierId);
      return profile.active() && "ACTIVE".equalsIgnoreCase(profile.accountStatus());
    } catch (ResponseStatusException e) {
      if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
        return false;
      }
      throw e;
    }
  }

  private CourierProfile mapRow(ResultSet rs) throws SQLException {
    UUID profileId = rs.getObject("profile_id", UUID.class);
    UUID userId = rs.getObject("user_id", UUID.class);
    String email = rs.getString("email");
    String name = rs.getString("display_name");
    String phone = rs.getString("phone");
    String accountStatus = rs.getString("account_status");
    if (accountStatus == null || accountStatus.isBlank()) accountStatus = "ACTIVE";
    boolean isProfileActive = rs.getBoolean("is_active");
    boolean effectiveActive = isProfileActive && "ACTIVE".equalsIgnoreCase(accountStatus);
    String assignedZone = rs.getString("assigned_zone");
    String vehicleType = rs.getString("vehicle_type");
    OffsetDateTime createdAt = rs.getObject("created_at", OffsetDateTime.class);
    OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);

    return new CourierProfile(
        profileId,
        userId,
        email,
        name,
        phone,
        effectiveActive,
        accountStatus,
        assignedZone,
        vehicleType,
        createdAt,
        updatedAt);
  }

  @Transactional
  public CourierProfile createCourierByAdmin(com.zentro.identitypartner.courier.dto.CreateCourierAdminRequest request, Jwt actor) {
    if (actor == null || !UserProfileService.roles(actor).contains("DISPATCHER")) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only dispatchers can create couriers.");
    }
    String email = request.email().trim().toLowerCase();
    String name = request.name().trim();
    String phone = request.phone() == null ? null : request.phone().trim();

    List<UUID> existingIds = jdbc.queryForList(
        "SELECT id FROM identity.app_user WHERE LOWER(email) = ?",
        UUID.class,
        email);
    if (!existingIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with email " + email + " already exists.");
    }

    if (keycloakAdmin.userExistsByEmail(email)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with email " + email + " already exists in Keycloak.");
    }

    String tempPassword = generateTempPassword();
    String keycloakSubject = keycloakAdmin.createCourierUser(email, name, phone, tempPassword);

    UUID appUserId = jdbc.queryForObject("""
        INSERT INTO identity.app_user (keycloak_subject, email, display_name, phone, account_status)
        VALUES (?, ?, ?, ?, 'ACTIVE')
        RETURNING id
        """, UUID.class, keycloakSubject, email, name, phone);

    jdbc.update("""
        INSERT INTO identity.courier_profile (app_user_id, is_active, assigned_zone, vehicle_type)
        VALUES (?, true, ?, ?)
        """, appUserId, clean(request.assignedZone()), clean(request.vehicleType()));

    try {
      emailSender.sendCourierInvite(email, name, tempPassword);
    } catch (Exception ex) {
      log.warn("Failed to send courier invite email to {}: {}", email, ex.getMessage());
    }

    return getByCourierId(appUserId);
  }

  private static String generateTempPassword() {
    String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    java.security.SecureRandom random = new java.security.SecureRandom();
    StringBuilder sb = new StringBuilder("Zentro@");
    for (int i = 0; i < 6; i++) {
      sb.append(chars.charAt(random.nextInt(chars.length())));
    }
    return sb.toString();
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
