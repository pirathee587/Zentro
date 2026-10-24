package com.zentro.identitypartner.merchant;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants")
@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER')")
public class MerchantApplicationController {
  private final MerchantApplicationService applications;

  public MerchantApplicationController(MerchantApplicationService applications) {
    this.applications = applications;
  }

  @GetMapping("/me")
  public MerchantApplication getCurrent(@AuthenticationPrincipal Jwt jwt) {
    return applications.current(jwt);
  }

  @PostMapping
  @PreAuthorize("hasRole('CUSTOMER')")
  public MerchantApplication apply(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateMerchantApplicationRequest request) {
    return applications.createOrReapply(jwt, request);
  }
}
