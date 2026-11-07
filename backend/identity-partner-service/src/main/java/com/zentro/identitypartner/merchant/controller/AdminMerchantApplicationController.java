package com.zentro.identitypartner.merchant.controller;

import com.zentro.identitypartner.merchant.dto.MerchantApiKeyMetadata;
import com.zentro.identitypartner.merchant.dto.MerchantApplication;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.service.MerchantApiKeyService;
import com.zentro.identitypartner.merchant.service.MerchantApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
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
  private final MerchantApiKeyService keys;

  public AdminMerchantApplicationController(
      MerchantApplicationService applications,
      MerchantApiKeyService keys) {
    this.applications = applications;
    this.keys = keys;
  }

  @GetMapping
  public List<MerchantApplication> listPending() {
    return applications.listPending();
  }

  @PatchMapping("/{applicationId}")
  public MerchantApplication review(
      @PathVariable("applicationId") UUID applicationId,
      @AuthenticationPrincipal Jwt reviewer,
      @Valid @RequestBody ReviewMerchantApplicationRequest request) {
    return applications.review(applicationId, reviewer, request);
  }

  @GetMapping("/{applicationId}/api-key")
  public MerchantApiKeyMetadata getMerchantApiKey(@PathVariable("applicationId") UUID applicationId) {
    return keys.getByApplicationId(applicationId);
  }

  @DeleteMapping("/{applicationId}/api-key")
  public MerchantApiKeyMetadata revokeMerchantApiKey(@PathVariable("applicationId") UUID applicationId) {
    return keys.revokeByApplicationId(applicationId);
  }
}
