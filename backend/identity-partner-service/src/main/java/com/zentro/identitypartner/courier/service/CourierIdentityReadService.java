package com.zentro.identitypartner.courier.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves existing records only; never calls user/profile provisioning. */
@Service
public class CourierIdentityReadService {
  private final JdbcTemplate jdbc;

  public CourierIdentityReadService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public record Identity(UUID userId, UUID courierProfileId) {}
  private record Row(UUID userId, UUID profileId, Boolean active, String accountStatus) {}

  /** Subject must come from the verified courier context, not a request argument. */
  @Transactional(readOnly = true)
  public Identity resolve(String verifiedSubject) {
    if (verifiedSubject == null || verifiedSubject.isBlank() || verifiedSubject.length() > 255) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid courier subject is required.");
    }
    Row row;
    try {
      row = jdbc.queryForObject("""
          SELECT u.id AS user_id, cp.id AS profile_id, cp.is_active, u.account_status
          FROM identity.app_user u
          LEFT JOIN identity.courier_profile cp ON cp.app_user_id = u.id
          WHERE u.keycloak_subject = ?
          """, (rs, index) -> new Row(rs.getObject("user_id", UUID.class),
              rs.getObject("profile_id", UUID.class), rs.getObject("is_active", Boolean.class),
              rs.getString("account_status")), verifiedSubject);
    } catch (EmptyResultDataAccessException missing) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier identity is not registered.");
    }
    if (row == null || row.profileId() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier profile is not registered.");
    }
    if (!Boolean.TRUE.equals(row.active()) || !"ACTIVE".equalsIgnoreCase(row.accountStatus())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Courier is not eligible.");
    }
    return new Identity(row.userId(), row.profileId());
  }
}
