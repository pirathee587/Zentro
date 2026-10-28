package com.zentro.identitypartner.user;

import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/users")
@PreAuthorize("hasAnyRole('CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER')")
public class UserProfileController {
  private final UserProfileService profiles;

  public UserProfileController(UserProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping("/me")
  public UserProfile getCurrent(@AuthenticationPrincipal Jwt jwt) {
    return profiles.current(jwt);
  }

  @PatchMapping("/me")
  public UserProfile updateCurrent(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateUserProfileRequest request) {
    return profiles.update(jwt, request);
  }

  @GetMapping("/{userId}")
  public UserProfile getById(@AuthenticationPrincipal Jwt jwt, @PathVariable("userId") UUID userId) {
    return profiles.getByIdForCaller(jwt, userId);
  }

  @PatchMapping("/{userId}")
  public UserProfile updateById(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable("userId") UUID userId,
      @Valid @RequestBody UpdateUserProfileRequest request) {
    return profiles.updateByIdForCaller(jwt, userId, request);
  }
}
