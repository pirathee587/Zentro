package com.zentro.identitypartner.user;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER')")
public class UserProfileController {
  private final UserProfileService profiles;

  public UserProfileController(UserProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  public UserProfile getCurrent(@AuthenticationPrincipal Jwt jwt) {
    return profiles.current(jwt);
  }

  @PatchMapping
  public UserProfile updateCurrent(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateUserProfileRequest request) {
    return profiles.update(jwt, request);
  }
}
