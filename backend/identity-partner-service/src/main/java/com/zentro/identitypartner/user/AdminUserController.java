package com.zentro.identitypartner.user;

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

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('DISPATCHER')")
public class AdminUserController {
  private final UserProfileService profiles;

  public AdminUserController(UserProfileService profiles) {
    this.profiles = profiles;
  }

  @GetMapping
  public List<UserProfile> listUsers(
      @RequestParam(name = "status", required = false) String status,
      @RequestParam(name = "search", required = false) String search) {
    return profiles.listUsers(status, search);
  }

  @GetMapping("/{userId}")
  public UserProfile getUser(@PathVariable("userId") UUID userId) {
    return profiles.getById(userId);
  }

  @PatchMapping("/{userId}")
  public UserProfile updateUser(
      @PathVariable("userId") UUID userId,
      @AuthenticationPrincipal Jwt actor,
      @Valid @RequestBody AdminUpdateUserRequest request) {
    return profiles.adminUpdate(userId, actor, request);
  }
}

