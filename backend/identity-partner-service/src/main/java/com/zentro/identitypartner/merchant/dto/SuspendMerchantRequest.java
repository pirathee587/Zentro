package com.zentro.identitypartner.merchant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for suspending an approved merchant (APPROVED → SUSPENDED).
 * A non-blank suspension reason is required so that the action is auditable.
 */
public record SuspendMerchantRequest(
    @NotBlank @Size(max = 1000) String reason) {}
