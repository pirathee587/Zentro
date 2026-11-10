package com.zentro.dispatchcourier.security;

import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Proposed #133 contract: pending security-owner approval, not ready for deployment.
 * Called only after the resource-server filter has validated the bearer JWT.
 */
@Component("dispatchPermissions")
public final class DispatchPermissions {
  private static final Set<String> KNOWN = Set.of(
      "DISPATCH_VIEW", "DISPATCH_ASSIGN", "DISPATCH_REASSIGN", "COURIER_MANAGE");

  public boolean has(Authentication authentication, String permission) {
    if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()
        || permission == null || !KNOWN.contains(permission)
        || token.getAuthorities().stream().noneMatch(a -> "ROLE_DISPATCHER".equals(a.getAuthority()))) {
      return false;
    }
    Object claim = token.getToken().getClaims().get("permissions");
    if (!(claim instanceof List<?> permissions)) return false;
    for (Object value : permissions) {
      if (!(value instanceof String name) || name.isBlank() || !name.equals(name.strip())) return false;
    }
    return permissions.contains(permission);
  }
}
