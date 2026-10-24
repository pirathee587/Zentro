package com.zentro.identitypartner.merchant;

import com.zentro.identitypartner.user.UserProfile;
import com.zentro.identitypartner.user.UserProfileService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantApplicationService {
  private static final RowMapper<MerchantApplication> ROW_MAPPER = MerchantApplicationService::mapApplication;

  private final JdbcTemplate jdbc;
  private final UserProfileService profiles;

  public MerchantApplicationService(JdbcTemplate jdbc, UserProfileService profiles) {
    this.jdbc = jdbc;
    this.profiles = profiles;
  }

  public MerchantApplication current(Jwt jwt) {
    UserProfile user = profiles.current(jwt);
    try {
      return jdbc.queryForObject("""
          SELECT id, app_user_id, business_name, business_reg_no, contact_phone, status,
                 rejection_reason, created_at, reviewed_at
            FROM identity.merchant_application
           WHERE app_user_id = ?
          """, ROW_MAPPER, user.id());
    } catch (EmptyResultDataAccessException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No merchant application exists for this account.");
    }
  }

  @Transactional
  public MerchantApplication createOrReapply(Jwt jwt, CreateMerchantApplicationRequest request) {
    UserProfile user = profiles.current(jwt);
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
            reviewed_by_subject = NULL,
            reviewed_at = NULL,
            updated_at = now()
        WHERE identity.merchant_application.status = 'REJECTED'
        RETURNING id, app_user_id, business_name, business_reg_no, contact_phone, status,
                  rejection_reason, created_at, reviewed_at
        """, ROW_MAPPER, user.id(), request.businessName().trim(), clean(request.businessRegNo()), request.contactPhone().trim());
    if (saved.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A merchant application is already pending or approved.");
    }
    return saved.getFirst();
  }

  public List<MerchantApplication> listPending() {
    return jdbc.query("""
        SELECT id, app_user_id, business_name, business_reg_no, contact_phone, status,
               rejection_reason, created_at, reviewed_at
          FROM identity.merchant_application
         WHERE status = 'PENDING'
         ORDER BY created_at
        """, ROW_MAPPER);
  }

  @Transactional
  public MerchantApplication review(UUID applicationId, Jwt reviewer, ReviewMerchantApplicationRequest request) {
    String status = request.decision().name();
    String reason = clean(request.rejectionReason());
    if ("REJECTED".equals(status) && reason == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A rejection reason is required.");
    }
    List<MerchantApplication> updated = jdbc.query("""
        UPDATE identity.merchant_application
           SET status = ?,
               rejection_reason = ?,
               reviewed_by_subject = ?,
               reviewed_at = now(),
               updated_at = now()
         WHERE id = ? AND status = 'PENDING'
        RETURNING id, app_user_id, business_name, business_reg_no, contact_phone, status,
                  rejection_reason, created_at, reviewed_at
        """, ROW_MAPPER, status, "REJECTED".equals(status) ? reason : null, reviewer.getSubject(), applicationId);
    if (!updated.isEmpty()) return updated.getFirst();

    boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM identity.merchant_application WHERE id = ?)", Boolean.class, applicationId));
    if (!exists) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant application was not found.");
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Only pending applications can be reviewed.");
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
        rs.getObject("created_at", java.time.OffsetDateTime.class),
        rs.getObject("reviewed_at", java.time.OffsetDateTime.class));
  }

  private static String clean(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
