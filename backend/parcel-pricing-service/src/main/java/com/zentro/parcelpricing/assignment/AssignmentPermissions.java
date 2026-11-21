package com.zentro.parcelpricing.assignment;

import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Mirrors S3 dispatcher permission validation without changing token issuance. */
@Component("assignmentPermissions")
public final class AssignmentPermissions {
  private static final Set<String> KNOWN = Set.of(
  "DISPATCH_VIEW", "DISPATCH_ASSIGN", "DISPATCH_REASSIGN", "COURIER_MANAGE");
  public boolean has(Authentication authentication, String permission) {
    if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()
    || permission == null || !KNOWN.contains(permission)
    || token.getAuthorities().stream().noneMatch(a -> "ROLE_DISPATCHER".equals(a.getAuthority()))) {
      return false;
    }
    return permits(token.getToken(), permission);
  }
  public static boolean permits(org.springframework.security.oauth2.jwt.Jwt jwt, String permission) {
    Object claim = jwt.getClaims().get("permissions");
    if (!(claim instanceof List<?> permissions)) return false;
    for (Object value : permissions) {
      if (!(value instanceof String name) || name.isBlank() || !name.equals(name.strip())) return false;
    }
    return permissions.contains(permission);
  }
}
