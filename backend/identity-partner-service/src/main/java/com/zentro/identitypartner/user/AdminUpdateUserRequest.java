package com.zentro.identitypartner.user;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record AdminUpdateUserRequest(
    @Size(max = 200, message = "Name must not exceed 200 characters")
    String name,

    @Size(max = 32, message = "Phone must not exceed 32 characters")
    String phone,

    @Pattern(regexp = "^(ACTIVE|DISABLED|SUSPENDED)$", message = "accountStatus must be ACTIVE, DISABLED, or SUSPENDED")
    String accountStatus
) {
  @JsonAnySetter
  public void rejectUnknownField(String fieldName, Object value) {
    throw new IllegalArgumentException("Unknown update field: " + fieldName);
  }
}
