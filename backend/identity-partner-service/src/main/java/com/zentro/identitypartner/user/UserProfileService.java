package com.zentro.identitypartner.user;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserProfileService {
  private static final String PROFILE_SQL = """
      SELECT u.id, u.keycloak_subject, u.email, u.display_name, u.phone,
             COALESCE(m.status, '') AS merchant_status
        FROM identity.app_user u
        LEFT JOIN identity.merchant_application m ON m.app_user_id = u.id
       WHERE u.keycloak_subject = ?
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

  private UserProfile select(String subject, List<String> roles) {
    return jdbc.queryForObject(PROFILE_SQL, (rs, row) -> {
      String merchantStatus = rs.getString("merchant_status");
      return new UserProfile(
          rs.getObject("id", UUID.class),
          rs.getString("email"),
          rs.getString("display_name"),
          rs.getString("phone"),
          true,
          "ACTIVE",
          merchantStatus.isBlank() ? null : merchantStatus,
          roles);
    }, subject);
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

  private static List<String> roles(Jwt jwt) {
    Object realmAccess = jwt.getClaims().get("realm_access");
    if (!(realmAccess instanceof Map<?, ?> claims) || !(claims.get("roles") instanceof List<?> values)) return List.of();
    return values.stream().filter(String.class::isInstance).map(String.class::cast)
        .filter(role -> List.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER").contains(role)).toList();
  }
}
