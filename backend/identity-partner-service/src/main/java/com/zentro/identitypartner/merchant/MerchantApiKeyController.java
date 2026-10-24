package com.zentro.identitypartner.merchant;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchants/api-key")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantApiKeyController {
  private final MerchantApiKeyService keys;

  public MerchantApiKeyController(MerchantApiKeyService keys) {
    this.keys = keys;
  }

  @GetMapping
  public MerchantApiKeyMetadata current(@AuthenticationPrincipal Jwt jwt) {
    return keys.current(jwt);
  }

  @PostMapping
  public IssuedMerchantApiKey issueOrRotate(@AuthenticationPrincipal Jwt jwt) {
    return keys.issueOrRotate(jwt);
  }

  @DeleteMapping
  public MerchantApiKeyMetadata revoke(@AuthenticationPrincipal Jwt jwt) {
    return keys.revoke(jwt);
  }
}
