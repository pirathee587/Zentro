package com.zentro.identitypartner.merchant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewMerchantApplicationRequest(
    @NotNull MerchantDecision decision,
    @Size(max = 1000) String rejectionReason) {
  public enum MerchantDecision { APPROVED, REJECTED }
}
