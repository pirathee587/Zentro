package com.zentro.identitypartner.merchant.service;

import com.zentro.identitypartner.merchant.dto.IssuedMerchantApiKey;
import com.zentro.identitypartner.merchant.dto.MerchantApiKeyMetadata;
import com.zentro.identitypartner.merchant.dto.ValidatedMerchantApiKey;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.service.UserProfileService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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

    // Generate cryptographically secure secret; retry once in rare prefix collision
    for (int attempt = 0; attempt < 2; attempt++) {
      byte[] randomBytes = new byte[32];
      RANDOM.nextBytes(randomBytes);
      String suffix = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
      String secret = "zn_live_" + suffix;
      String prefix = "zn_live_" + suffix.substring(0, 8);

      try {
        MerchantApiKeyMetadata metadata = jdbc.queryForObject("""
            INSERT INTO identity.merchant_api_key (app_user_id, key_prefix, key_hash)
            VALUES (?, ?, ?)
            ON CONFLICT (app_user_id) DO UPDATE SET
                key_prefix = EXCLUDED.key_prefix,
                key_hash = EXCLUDED.key_hash,
                created_at = COALESCE(identity.merchant_api_key.created_at, now()),
                rotated_at = now(),
                revoked_at = NULL
            RETURNING id, key_prefix, created_at, rotated_at, revoked_at
            """, ROW_MAPPER, user.id(), prefix, sha256(secret));
        return new IssuedMerchantApiKey(secret, metadata);
      } catch (DuplicateKeyException ex) {
        if (attempt == 1) throw ex;
      }
    }
    throw new ResponseStatusException(HttpStatus.CONFLICT, "Could not generate a unique API key credential.");
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
    if (updated.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No API key exists for this merchant.");
    }
    return updated.getFirst();
  }

  public MerchantApiKeyMetadata getByApplicationId(UUID applicationId) {
    UUID appUserId = findMerchantUserId(applicationId);
    List<MerchantApiKeyMetadata> keys = jdbc.query("""
        SELECT id, key_prefix, created_at, rotated_at, revoked_at
          FROM identity.merchant_api_key
         WHERE app_user_id = ?
        """, ROW_MAPPER, appUserId);
    if (keys.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No API key exists for this merchant.");
    }
    return keys.getFirst();
  }

  @Transactional
  public MerchantApiKeyMetadata revokeByApplicationId(UUID applicationId) {
    UUID appUserId = findMerchantUserId(applicationId);
    List<MerchantApiKeyMetadata> updated = jdbc.query("""
        UPDATE identity.merchant_api_key
           SET revoked_at = COALESCE(revoked_at, now())
         WHERE app_user_id = ?
        RETURNING id, key_prefix, created_at, rotated_at, revoked_at
        """, ROW_MAPPER, appUserId);
    if (updated.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No API key exists for this merchant.");
    }
    return updated.getFirst();
  }

  public Optional<ValidatedMerchantApiKey> validateKey(String rawSecret) {
    if (rawSecret == null || !rawSecret.startsWith("zn_live_") || rawSecret.length() < 16) {
      return Optional.empty();
    }
    String hash = sha256(rawSecret);
    List<ValidatedMerchantApiKey> matches = jdbc.query("""
        SELECT k.id, k.app_user_id, k.key_prefix,
               (k.revoked_at IS NULL AND u.account_status = 'ACTIVE' AND m.status = 'APPROVED') AS is_active
          FROM identity.merchant_api_key k
          JOIN identity.app_user u ON u.id = k.app_user_id
          JOIN identity.merchant_application m ON m.app_user_id = u.id
         WHERE k.key_hash = ?
        """, (rs, row) -> new ValidatedMerchantApiKey(
            rs.getObject("id", UUID.class),
            rs.getObject("app_user_id", UUID.class),
            rs.getString("key_prefix"),
            rs.getBoolean("is_active")
        ), hash);
    return matches.isEmpty() ? Optional.empty() : Optional.of(matches.getFirst());
  }

  private UUID findMerchantUserId(UUID applicationId) {
    List<UUID> userIds = jdbc.query("""
        SELECT app_user_id
          FROM identity.merchant_application
         WHERE id = ?
        """, (rs, row) -> rs.getObject("app_user_id", UUID.class), applicationId);
    if (userIds.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant application was not found.");
    }
    return userIds.getFirst();
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
