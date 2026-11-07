package com.zentro.identitypartner.merchant.controller;

import com.zentro.identitypartner.merchant.dto.MerchantApplication;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.SuspendMerchantRequest;
import com.zentro.identitypartner.merchant.service.MerchantApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dispatcher-only endpoints for managing the merchant application approval lifecycle.
 *
 * <p>All endpoints require {@code ROLE_DISPATCHER}. The service layer enforces state
 * preconditions independently of this controller to maintain defense-in-depth.
 *
 * <p>Supported lifecycle transitions:
 * <ul>
 *   <li>{@code PATCH /{id}}           — review a PENDING application (APPROVED, REJECTED, SUSPENDED)</li>
 *   <li>{@code PATCH /{id}/suspend}   — suspend an APPROVED merchant (APPROVED → SUSPENDED)</li>
 *   <li>{@code PATCH /{id}/reinstate} — reinstate a SUSPENDED merchant (SUSPENDED → APPROVED)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/merchants")
@PreAuthorize("hasRole('DISPATCHER')")
public class AdminMerchantApplicationController {
  private final MerchantApplicationService applications;

  public AdminMerchantApplicationController(MerchantApplicationService applications) {
    this.applications = applications;
  }

  /**
   * Lists merchant applications, optionally filtered by status.
   *
   * @param status optional filter: PENDING, APPROVED, REJECTED, or SUSPENDED; omit to return all.
   */
  @GetMapping
  public List<MerchantApplication> list(
      @RequestParam(name = "status", required = false) String status) {
    return applications.list(status);
  }

  /**
   * Reviews a PENDING merchant application.
   * Allowed decisions: APPROVED, REJECTED (requires reason), SUSPENDED (requires reason).
   */
  @PatchMapping("/{applicationId}")
  public MerchantApplication review(
      @PathVariable("applicationId") UUID applicationId,
      @AuthenticationPrincipal Jwt reviewer,
      @Valid @RequestBody ReviewMerchantApplicationRequest request) {
    return applications.review(applicationId, reviewer, request);
  }

  /**
   * Suspends an APPROVED merchant (APPROVED → SUSPENDED).
   * A non-blank suspension reason is required.
   */
  @PatchMapping("/{applicationId}/suspend")
  public MerchantApplication suspend(
      @PathVariable("applicationId") UUID applicationId,
      @AuthenticationPrincipal Jwt reviewer,
      @Valid @RequestBody SuspendMerchantRequest request) {
    return applications.suspend(applicationId, reviewer, request.reason());
  }

  /**
   * Reinstates a SUSPENDED merchant (SUSPENDED → APPROVED).
   * No request body required; the dispatcher JWT identifies the actor.
   */
  @PatchMapping("/{applicationId}/reinstate")
  public MerchantApplication reinstate(
      @PathVariable("applicationId") UUID applicationId,
      @AuthenticationPrincipal Jwt reviewer) {
    return applications.reinstate(applicationId, reviewer);
  }
}
