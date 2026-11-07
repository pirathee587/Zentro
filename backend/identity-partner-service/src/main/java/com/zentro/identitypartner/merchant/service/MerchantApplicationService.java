package com.zentro.identitypartner.merchant.service;

import com.zentro.identitypartner.merchant.dto.MerchantApplication;
import com.zentro.identitypartner.merchant.dto.CreateMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest.MerchantDecision;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.service.UserProfileService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Business logic for the merchant application approval lifecycle.
 *
 * <p>Permitted status transitions:
 * <ul>
 *   <li>PENDING  → APPROVED  (dispatcher review)</li>
 *   <li>PENDING  → REJECTED  (dispatcher review; rejection reason required)</li>
 *   <li>PENDING  → SUSPENDED (dispatcher review; suspension reason required)</li>
 *   <li>APPROVED → SUSPENDED (dispatcher suspend; suspension reason required)</li>
 *   <li>SUSPENDED→ APPROVED  (dispatcher reinstate)</li>
 *   <li>REJECTED → PENDING   (merchant re-applies via createOrReapply)</li>
 * </ul>
 *
 * <p>All status-changing operations are DISPATCHER-only and enforced both at the controller
 * ({@code @PreAuthorize}) and in this service. Merchants cannot change their own approval status.
 */
@Service
public class MerchantApplicationService {
  private static final RowMapper<MerchantApplication> ROW_MAPPER = MerchantApplicationService::mapApplication;

  /** Status values that block approved-merchant operations. */
  private static final List<String> BLOCKED_STATUSES = List.of("PENDING", "REJECTED", "SUSPENDED");

  private final JdbcTemplate jdbc;
  private final UserProfileService profiles;

  public MerchantApplicationService(JdbcTemplate jdbc, UserProfileService profiles) {
    this.jdbc = jdbc;
    this.profiles = profiles;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Merchant-facing reads
  // ─────────────────────────────────────────────────────────────────────────

  /** Returns the caller's own merchant application; 404 if none exists. */
  public MerchantApplication current(Jwt jwt) {
    UserProfile user = profiles.current(jwt);
    try {
      return jdbc.queryForObject("""
          SELECT id, app_user_id, business_name, business_reg_no, contact_phone, status,
                 rejection_reason, suspension_reason, created_at, reviewed_at
            FROM identity.merchant_application
           WHERE app_user_id = ?
          """, ROW_MAPPER, user.id());
    } catch (EmptyResultDataAccessException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No merchant application exists for this account.");
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Merchant-facing writes (re-apply after rejection)
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Creates a new merchant application or re-applies after rejection.
   * Only a user in REJECTED state may re-apply; PENDING and APPROVED applications cannot be overwritten.
   */
  @Transactional
  public MerchantApplication createOrReapply(Jwt jwt, CreateMerchantApplicationRequest request) {
    UserProfile user = profiles.current(jwt);
    try {
      List<MerchantApplication> saved = jdbc.query("""
          INSERT INTO identity.merchant_application
              (app_user_id, business_name, business_reg_no, contact_phone)
          VALUES (?, ?, ?, ?)
          ON CONFLICT (app_user_id) DO UPDATE SET
              business_name = EXCLUDED.business_name,
              business_reg_no = EXCLUDED.business_reg_no,
              contact_phone = EXCLUDED.contact_phone,
              status = 'PENDING',
              rejection_reason = NULL,
              suspension_reason = NULL,
              reviewed_by_subject = NULL,
              reviewed_at = NULL,
              updated_at = now()
          WHERE identity.merchant_application.status = 'REJECTED'
          RETURNING id, app_user_id, business_name, business_reg_no, contact_phone, status,
                    rejection_reason, suspension_reason, created_at, reviewed_at
          """, ROW_MAPPER, user.id(), request.businessName().trim(),
          normalizeBusinessRegNo(request.businessRegNo()), request.contactPhone().trim());
      if (saved.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "A merchant application is already pending or approved.");
      }
      return saved.getFirst();
    } catch (DuplicateKeyException duplicate) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "The business details conflict with an existing merchant application.");
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Dispatcher-facing reads
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Lists merchant applications, optionally filtered by status.
   *
   * @param status optional status filter (PENDING, APPROVED, REJECTED, SUSPENDED); {@code null} returns all.
   */
  public List<MerchantApplication> list(String status) {
    if (status != null) {
      String normalized = status.trim().toUpperCase(Locale.ROOT);
      if (!List.of("PENDING", "APPROVED", "REJECTED", "SUSPENDED").contains(normalized)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Invalid status filter. Must be one of: PENDING, APPROVED, REJECTED, SUSPENDED.");
      }
      return jdbc.query("""
          SELECT id, app_user_id, business_name, business_reg_no, contact_phone, status,
                 rejection_reason, suspension_reason, created_at, reviewed_at
            FROM identity.merchant_application
           WHERE status = ?
           ORDER BY created_at
          """, ROW_MAPPER, normalized);
    }
    return jdbc.query("""
        SELECT id, app_user_id, business_name, business_reg_no, contact_phone, status,
               rejection_reason, suspension_reason, created_at, reviewed_at
          FROM identity.merchant_application
         ORDER BY created_at
        """, ROW_MAPPER);
  }

  /** @deprecated Use {@link #list(String)} with {@code status = "PENDING"} instead. */
  @Deprecated(forRemoval = true)
  public List<MerchantApplication> listPending() {
    return list("PENDING");
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Dispatcher-facing status transitions
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Reviews a PENDING merchant application.
   *
   * <p>Allowed transitions from PENDING:
   * <ul>
   *   <li>PENDING → APPROVED</li>
   *   <li>PENDING → REJECTED (rejection reason required)</li>
   *   <li>PENDING → SUSPENDED (suspension reason required)</li>
   * </ul>
   *
   * @throws ResponseStatusException 400 if rejection/suspension reason is missing
   * @throws ResponseStatusException 404 if the application does not exist
   * @throws ResponseStatusException 409 if the application is not in PENDING state
   */
  @Transactional
  public MerchantApplication review(UUID applicationId, Jwt reviewer, ReviewMerchantApplicationRequest request) {
    String status = request.decision().name();
    String reason = clean(request.rejectionReason());

    if ("REJECTED".equals(status) && reason == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rejection reason is required.");
    }
    if ("SUSPENDED".equals(status) && reason == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A suspension reason is required.");
    }

    List<MerchantApplication> updated = jdbc.query("""
        UPDATE identity.merchant_application
           SET status = ?,
               rejection_reason = ?,
               suspension_reason = ?,
               reviewed_by_subject = ?,
               reviewed_at = now(),
               updated_at = now()
         WHERE id = ? AND status = 'PENDING'
        RETURNING id, app_user_id, business_name, business_reg_no, contact_phone, status,
                  rejection_reason, suspension_reason, created_at, reviewed_at
        """, ROW_MAPPER,
        status,
        "REJECTED".equals(status) ? reason : null,
        "SUSPENDED".equals(status) ? reason : null,
        reviewer.getSubject(),
        applicationId);

    if (!updated.isEmpty()) return updated.getFirst();

    return handleMissingOrNonPending(applicationId,
        "Only pending applications can be reviewed.");
  }

  /**
   * Suspends an APPROVED merchant application (APPROVED → SUSPENDED).
   *
   * <p>A suspension reason is required. Only a Dispatcher may call this.
   *
   * @throws ResponseStatusException 400 if the suspension reason is blank
   * @throws ResponseStatusException 404 if the application does not exist
   * @throws ResponseStatusException 409 if the application is not in APPROVED state
   */
  @Transactional
  public MerchantApplication suspend(UUID applicationId, Jwt reviewer, String rawReason) {
    String reason = clean(rawReason);
    if (reason == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A suspension reason is required.");
    }

    List<MerchantApplication> updated = jdbc.query("""
        UPDATE identity.merchant_application
           SET status = 'SUSPENDED',
               suspension_reason = ?,
               reviewed_by_subject = ?,
               reviewed_at = now(),
               updated_at = now()
         WHERE id = ? AND status = 'APPROVED'
        RETURNING id, app_user_id, business_name, business_reg_no, contact_phone, status,
                  rejection_reason, suspension_reason, created_at, reviewed_at
        """, ROW_MAPPER, reason, reviewer.getSubject(), applicationId);

    if (!updated.isEmpty()) return updated.getFirst();

    return handleMissingOrNonPending(applicationId,
        "Only approved merchants can be suspended.");
  }

  /**
   * Reinstates a SUSPENDED merchant application (SUSPENDED → APPROVED).
   *
   * <p>Only a Dispatcher may call this.
   *
   * @throws ResponseStatusException 404 if the application does not exist
   * @throws ResponseStatusException 409 if the application is not in SUSPENDED state
   */
  @Transactional
  public MerchantApplication reinstate(UUID applicationId, Jwt reviewer) {
    List<MerchantApplication> updated = jdbc.query("""
        UPDATE identity.merchant_application
           SET status = 'APPROVED',
               suspension_reason = NULL,
               reviewed_by_subject = ?,
               reviewed_at = now(),
               updated_at = now()
         WHERE id = ? AND status = 'SUSPENDED'
        RETURNING id, app_user_id, business_name, business_reg_no, contact_phone, status,
                  rejection_reason, suspension_reason, created_at, reviewed_at
        """, ROW_MAPPER, reviewer.getSubject(), applicationId);

    if (!updated.isEmpty()) return updated.getFirst();

    return handleMissingOrNonPending(applicationId,
        "Only suspended merchants can be reinstated.");
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Internal helpers
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * Looks up whether the application exists to distinguish 404 from 409.
   *
   * @throws ResponseStatusException 404 if the application does not exist
   * @throws ResponseStatusException 409 always (the state precondition was not met)
   */
  private MerchantApplication handleMissingOrNonPending(UUID applicationId, String conflictMessage) {
    boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM identity.merchant_application WHERE id = ?)", Boolean.class, applicationId));
    if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant application was not found.");
    throw new ResponseStatusException(HttpStatus.CONFLICT, conflictMessage);
  }

  private static MerchantApplication mapApplication(ResultSet rs, int row) throws SQLException {
    return new MerchantApplication(
        rs.getObject("id", UUID.class),
        rs.getObject("app_user_id", UUID.class),
        rs.getString("business_name"),
        rs.getString("business_reg_no"),
        rs.getString("contact_phone"),
        rs.getString("status"),
        rs.getString("rejection_reason"),
        rs.getString("suspension_reason"),
        rs.getObject("created_at", java.time.OffsetDateTime.class),
        rs.getObject("reviewed_at", java.time.OffsetDateTime.class));
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String normalizeBusinessRegNo(String value) {
    String cleaned = clean(value);
    return cleaned == null ? null : cleaned.toUpperCase(Locale.ROOT);
  }
}
