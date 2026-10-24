package com.zentro.identitypartner.merchant;

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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/merchants")
@PreAuthorize("hasRole('DISPATCHER')")
public class AdminMerchantApplicationController {
  private final MerchantApplicationService applications;

  public AdminMerchantApplicationController(MerchantApplicationService applications) {
    this.applications = applications;
  }

  @GetMapping
  public List<MerchantApplication> listPending() {
    return applications.listPending();
  }

  @PatchMapping("/{applicationId}")
  public MerchantApplication review(
      @PathVariable UUID applicationId,
      @AuthenticationPrincipal Jwt reviewer,
      @Valid @RequestBody ReviewMerchantApplicationRequest request) {
    return applications.review(applicationId, reviewer, request);
  }
}
