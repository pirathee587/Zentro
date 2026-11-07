package com.zentro.identitypartner.merchant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for a dispatcher review of a merchant application.
 *
 * <ul>
 *   <li>{@code APPROVED} — application is accepted; {@code rejectionReason} is ignored.</li>
 *   <li>{@code REJECTED} — application is declined; {@code rejectionReason} is required.</li>
 *   <li>{@code SUSPENDED} — an already-approved merchant is suspended; {@code rejectionReason} is
 *       used as the suspension reason and is required.</li>
 * </ul>
 *
 * <p>This DTO is used both for initial review ({@code PENDING → APPROVED/REJECTED}) and for
 * lifecycle management ({@code APPROVED → SUSPENDED} and {@code SUSPENDED → APPROVED}).
 * Service-layer validation rejects decisions that are not valid for the current state.
 */
public record ReviewMerchantApplicationRequest(
    @NotNull MerchantDecision decision,
    @Size(max = 1000) String rejectionReason) {

  /**
   * Allowed dispatcher decisions for a merchant application.
   *
   * <ul>
   *   <li>{@code APPROVED} — approve a pending application, or reinstate a suspended merchant.</li>
   *   <li>{@code REJECTED} — reject a pending application (requires a rejection reason).</li>
   *   <li>{@code SUSPENDED} — suspend an approved merchant (requires a suspension reason).</li>
   * </ul>
   */
  public enum MerchantDecision { APPROVED, REJECTED, SUSPENDED }
}
