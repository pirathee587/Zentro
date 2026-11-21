package com.zentro.identitypartner.courier.controller;

import com.zentro.identitypartner.courier.service.CourierIdentityReadService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only self lookup, deliberately separate from profile provisioning APIs. */
@RestController
public class CourierIdentityReadController {
  private final CourierIdentityReadService identities;
  public CourierIdentityReadController(CourierIdentityReadService identities) { this.identities = identities; }

  @GetMapping("/internal/courier-dashboard/identity")
  @PreAuthorize("hasRole('COURIER') and !hasRole('SERVICE')")
  public ResponseEntity<CourierIdentityReadService.Identity> current(@AuthenticationPrincipal Jwt courier) {
    return ResponseEntity.ok().header("Cache-Control", "no-store").body(identities.resolve(courier.getSubject()));
  }
}
