package com.zentro.dispatchcourier.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class DispatchPermissionsTest {
  private final DispatchPermissions permissions = new DispatchPermissions();

  // Authentication-context fixtures only: signed-token validation is covered by ZeroTrustJwtSecurityTest.
  private JwtAuthenticationToken token(Object claim, String role) {
    var jwt = Jwt.withTokenValue("fixture").header("alg", "fixture").subject("dispatcher");
    if (claim != null) jwt.claim("permissions", claim);
    return new JwtAuthenticationToken(jwt.build(), List.of(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"DISPATCH_VIEW", "DISPATCH_ASSIGN", "DISPATCH_REASSIGN", "COURIER_MANAGE"})
  void exactKnownPermissionsRequireDispatcher(String permission) {
    assertThat(permissions.has(token(List.of(permission), "DISPATCHER"), permission)).isTrue();
    for (String role : List.of("CUSTOMER", "MERCHANT", "COURIER", "SERVICE")) {
      assertThat(permissions.has(token(List.of(permission), role), permission)).isFalse();
    }
  }

  static Stream<Object> invalidClaims() {
    return Stream.of(null, "DISPATCH_VIEW", Map.of("DISPATCH_VIEW", true), List.of(),
        List.of("DISPATCH_ASSIGN"), List.of("dispatch_view"), List.of("PERM_DISPATCH_VIEW"),
        List.of("DISPATCH_VIEW", 7), List.of("DISPATCH_VIEW", ""),
        List.of("DISPATCH_VIEW", " DISPATCH_ASSIGN"), List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN "),
        Arrays.asList("DISPATCH_VIEW", null));
  }

  @ParameterizedTest @MethodSource("invalidClaims")
  void absentMalformedOrInsufficientClaimsDeny(Object claim) {
    assertThat(permissions.has(token(claim, "DISPATCHER"), "DISPATCH_VIEW")).isFalse();
  }

  @Test void duplicateAndUnknownNamesDoNotExpandGrants() {
    var auth = token(List.of("DISPATCH_VIEW", "DISPATCH_VIEW", "FUTURE_PERMISSION"), "DISPATCHER");
    assertThat(permissions.has(auth, "DISPATCH_VIEW")).isTrue();
    assertThat(permissions.has(auth, "DISPATCH_ASSIGN")).isFalse();
    assertThat(permissions.has(auth, "FUTURE_PERMISSION")).isFalse();
    assertThat(permissions.has(auth, null)).isFalse();
  }

  @Test void onlyAuthenticatedJwtContextsAreAccepted() {
    assertThat(permissions.has(null, "DISPATCH_VIEW")).isFalse();
    var auth = token(List.of("DISPATCH_VIEW"), "DISPATCHER");
    auth.setAuthenticated(false);
    assertThat(permissions.has(auth, "DISPATCH_VIEW")).isFalse();
    var other = UsernamePasswordAuthenticationToken.authenticated("dispatcher", "unused",
        List.of(new SimpleGrantedAuthority("ROLE_DISPATCHER"), new SimpleGrantedAuthority("PERM_DISPATCH_VIEW")));
    assertThat(permissions.has(other, "DISPATCH_VIEW")).isFalse();
  }
}
