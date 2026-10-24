package com.zentro.identitypartner.merchant;

import com.zentro.identitypartner.user.UserProfile;
import com.zentro.identitypartner.user.UserProfileService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MerchantApiKeyService {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final RowMapper<MerchantApiKeyMetadata> ROW_MAPPER = MerchantApiKeyService::mapMetadata;

  private final JdbcTemplate jdbc;
  private final UserProfileService profiles;

  public MerchantApiKeyService(JdbcTemplate jdbc, UserProfileService profiles) {
    this.jdbc = jdbc;
    this.profiles = profiles;
  }

  public MerchantApiKeyMetadata current(Jwt jwt) {
    UserProfile user = approvedMerchant(jwt);
    List<MerchantApiKeyMetadata> keys = jdbc.query("""
        SELECT id, key_prefix, created_at, rotated_at, revoked_at
          FROM identity.merchant_api_key
         WHERE app_user_id = ?
        """, ROW_MAPPER, user.id());
    return keys.isEmpty() ? null : keys.getFirst();
  }

  @Transactional
  public IssuedMerchantApiKey issueOrRotate(Jwt jwt) {
    UserProfile user = approvedMerchant(jwt);
    byte[] randomBytes = new byte[32];
    RANDOM.nextBytes(randomBytes);
    String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    String secret = "zn_live_" + suffix;
    String prefix = "zn_live_" + suffix.substring(0, 8);
    MerchantApiKeyMetadata metadata = jdbc.queryForObject("""
        INSERT INTO identity.merchant_api_key (app_user_id, key_prefix, key_hash)
        VALUES (?, ?, ?)
        ON CONFLICT (app_user_id) DO UPDATE SET
            key_prefix = EXCLUDED.key_prefix,
            key_hash = EXCLUDED.key_hash,
            created_at = now(),
            rotated_at = now(),
            revoked_at = NULL
        RETURNING id, key_prefix, created_at, rotated_at, revoked_at
        """, ROW_MAPPER, user.id(), prefix, sha256(secret));
    return new IssuedMerchantApiKey(secret, metadata);
  }

  @Transactional
  public MerchantApiKeyMetadata revoke(Jwt jwt) {
    UserProfile user = approvedMerchant(jwt);
    List<MerchantApiKeyMetadata> updated = jdbc.query("""
        UPDATE identity.merchant_api_key
           SET revoked_at = COALESCE(revoked_at, now())
         WHERE app_user_id = ?
        RETURNING id, key_prefix, created_at, rotated_at, revoked_at
        """, ROW_MAPPER, user.id());
    return updated.isEmpty() ? null : updated.getFirst();
  }

  private UserProfile approvedMerchant(Jwt jwt) {
    UserProfile user = profiles.current(jwt);
    Boolean allowed = jdbc.queryForObject("""
        SELECT EXISTS (
          SELECT 1
            FROM identity.app_user u
            JOIN identity.merchant_application m ON m.app_user_id = u.id
           WHERE u.id = ?
             AND u.account_status = 'ACTIVE'
             AND m.status = 'APPROVED'
        )
        """, Boolean.class, user.id());
    if (!Boolean.TRUE.equals(allowed)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "API keys are available to approved, active merchants only.");
    }
    return user;
  }

  private static MerchantApiKeyMetadata mapMetadata(ResultSet rs, int row) throws SQLException {
    java.time.OffsetDateTime revokedAt = rs.getObject("revoked_at", java.time.OffsetDateTime.class);
    return new MerchantApiKeyMetadata(
        rs.getObject("id", java.util.UUID.class),
        rs.getString("key_prefix"),
        rs.getObject("created_at", java.time.OffsetDateTime.class),
        rs.getObject("rotated_at", java.time.OffsetDateTime.class),
        revokedAt,
        revokedAt == null);
  }

  private static String sha256(String secret) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available.", impossible);
    }
  }
}
