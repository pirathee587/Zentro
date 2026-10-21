package com.zentro.security.ownership;

import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reusable resource-level ownership checks for controllers and services. */
public final class ResourceOwnership {
  private ResourceOwnership() { }
  public static boolean isOwner(Authentication authentication, String ownerSubject) {
    if (authentication == null || ownerSubject == null) return false;
    Object principal = authentication.getPrincipal();
    return principal instanceof Jwt jwt && Objects.equals(jwt.getSubject(), ownerSubject);
  }
}
