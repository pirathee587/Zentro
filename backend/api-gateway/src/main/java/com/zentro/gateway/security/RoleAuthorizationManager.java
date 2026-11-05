package com.zentro.gateway.security;

import com.zentro.gateway.constants.GatewayConstants;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.ReactiveAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.server.authorization.AuthorizationContext;

public final class RoleAuthorizationManager {
  private RoleAuthorizationManager() {}

  public static ReactiveAuthorizationManager<AuthorizationContext> has(String role) {
    return (authentication, context) -> authentication
        .map(auth -> new AuthorizationDecision(
            isHuman(auth) && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority()))))
        .defaultIfEmpty(new AuthorizationDecision(false));
  }

  public static ReactiveAuthorizationManager<AuthorizationContext> hasAny(String... roles) {
    List<String> allowedRoles = Arrays.asList(roles);
    return (authentication, context) -> authentication
        .map(auth -> new AuthorizationDecision(
            isHuman(auth) && auth.getAuthorities().stream().anyMatch(a -> allowedRoles.contains(a.getAuthority()))))
        .defaultIfEmpty(new AuthorizationDecision(false));
  }

  public static ReactiveAuthorizationManager<AuthorizationContext> humanAuthenticated() {
    return (authentication, context) -> authentication
        .map(auth -> new AuthorizationDecision(auth.isAuthenticated() && isHuman(auth)))
        .defaultIfEmpty(new AuthorizationDecision(false));
  }

  public static ReactiveAuthorizationManager<AuthorizationContext> notServiceOnly() {
    return (authentication, context) -> authentication
        .map(auth -> new AuthorizationDecision(!serviceOnly(auth)))
        .defaultIfEmpty(new AuthorizationDecision(true));
  }

  public static boolean serviceOnly(Authentication auth) {
    return auth.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .filter(a -> a.startsWith("ROLE_"))
        .allMatch(GatewayConstants.ROLE_SERVICE::equals)
        && auth.getAuthorities().stream().anyMatch(a -> GatewayConstants.ROLE_SERVICE.equals(a.getAuthority()));
  }

  public static boolean isHuman(Authentication auth) {
    return auth.getAuthorities().stream().anyMatch(a -> GatewayConstants.HUMAN_AUTHORITIES.contains(a.getAuthority()));
  }
}

