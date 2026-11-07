package com.zentro.identitypartner.user.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Body of {@code PATCH /api/v1/admin/users/{userId}}. Only these fields can be changed; email, Keycloak subject and
 * roles are never editable through this API. The full rules (trimming, Sri Lankan phone format, control characters)
 * are applied by the service with {@code UserInputRules}, and only to values that really change.
 *
 * <p>Any other JSON field is rejected with 400 naming the field (code {@code PROTECTED_FIELD} for identity and role
 * fields, {@code UNKNOWN_FIELD} otherwise). This applies to this request only; global JSON settings are unchanged.
 *
 * @param reason optional reason for the change (at most 500 characters), stored in the audit record
 */
public record AdminUpdateUserRequest(
    @Size(max = 200, message = "Name must not exceed 200 characters")
    String name,

    @Size(max = 32, message = "Phone must not exceed 32 characters")
    String phone,

    @Pattern(regexp = "(?i)^\\s*(ACTIVE|DISABLED|SUSPENDED)\\s*$",
        message = "accountStatus must be ACTIVE, DISABLED, or SUSPENDED")
    String accountStatus,

    @Size(max = 500, message = "Reason must not exceed 500 characters")
    String reason
) {
  public static final Set<String> FIELDS = Set.of("name", "phone", "accountStatus", "reason");

  /** Identity, role and system fields a dispatcher must never change here (compared case-insensitively). */
  public static final Set<String> PROTECTED_FIELDS = Set.of(
      "roles", "role", "realmroles", "realm_access", "email", "keycloaksubject", "keycloak_subject", "subject",
      "sub", "id", "userid", "accounttype", "enabled", "merchantstatus", "password", "createdat", "updatedat");

  /** Request without a reason (kept for existing callers). */
  public AdminUpdateUserRequest(String name, String phone, String accountStatus) {
    this(name, phone, accountStatus, null);
  }

  /** Reads the JSON object, rejecting protected and unknown fields and non-text values. */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static AdminUpdateUserRequest fromJson(Map<String, Object> body) {
    for (String key : body.keySet()) {
      if (!FIELDS.contains(key)) {
        String shown = safeName(key);
        if (PROTECTED_FIELDS.contains(key.toLowerCase(Locale.ROOT))) {
          throw new InvalidUserInputException(shown, "PROTECTED_FIELD",
              "Field '" + shown + "' cannot be changed through this API.");
        }
        throw new InvalidUserInputException(shown, "UNKNOWN_FIELD", "Unknown field '" + shown + "'.");
      }
    }
    return new AdminUpdateUserRequest(text(body, "name"), text(body, "phone"), text(body, "accountStatus"),
        text(body, "reason"));
  }

  /** True when at least one updatable field (name, phone or accountStatus) is present. */
  public boolean hasUpdatableField() {
    return name != null || phone != null || accountStatus != null;
  }

  private static String text(Map<String, Object> body, String field) {
    Object value = body.get(field);
    if (value == null || value instanceof String) {
      return (String) value;
    }
    throw new InvalidUserInputException(field, field + " must be a text value.");
  }

  /** The submitted field name as it may be shown back: letters, digits, '_', '-', '.', at most 64 characters. */
  private static String safeName(String key) {
    String cleaned = key.replaceAll("[^A-Za-z0-9_.-]", "");
    if (cleaned.isEmpty()) {
      return "(unnamed)";
    }
    return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
  }
}
