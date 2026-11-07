package com.zentro.identitypartner.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/v1/admin/users/{userId}}. Only these fields can be changed; email, Keycloak subject and
 * roles are never editable through this API. The full rules (trimming, Sri Lankan phone format, control characters)
 * are applied by the service with {@code UserInputRules}, and only to values that really change.
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
  /** Request without a reason (kept for existing callers). */
  public AdminUpdateUserRequest(String name, String phone, String accountStatus) {
    this(name, phone, accountStatus, null);
  }

  /** True when at least one updatable field (name, phone or accountStatus) is present. */
  public boolean hasUpdatableField() {
    return name != null || phone != null || accountStatus != null;
  }
}
