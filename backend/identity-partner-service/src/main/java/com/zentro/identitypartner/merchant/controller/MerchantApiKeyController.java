package com.zentro.identitypartner.merchant.controller;


import com.zentro.identitypartner.merchant.dto.IssuedMerchantApiKey;
import com.zentro.identitypartner.merchant.dto.MerchantApiKeyMetadata;
import com.zentro.identitypartner.merchant.service.MerchantApiKeyService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
  public ResponseEntity<IssuedMerchantApiKey> issueOrRotate(@AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(keys.issueOrRotate(jwt));
  }

  @DeleteMapping
  public MerchantApiKeyMetadata revoke(@AuthenticationPrincipal Jwt jwt) {
    return keys.revoke(jwt);
  }
}
