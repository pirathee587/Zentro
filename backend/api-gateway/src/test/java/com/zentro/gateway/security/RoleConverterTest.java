package com.zentro.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RoleConverterTest {
  private final RoleConverter converter = new RoleConverter();

  @Test
  void extractsOnlyAllowedRealmRolesWithRolePrefix() {
    Jwt jwt = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("realm_access", Map.of("roles", List.of(
            "CUSTOMER", "DISPATCHER", "ADMIN", "UNKNOWN_ROLE", "SERVICE")))
        .build();

    List<String> authorities = converter.convert(jwt)
        .map(GrantedAuthority::getAuthority)
        .collectList()
        .block();

    assertThat(authorities).containsExactly("ROLE_CUSTOMER", "ROLE_DISPATCHER", "ROLE_SERVICE");
  }

  @Test
  void returnsEmptyFluxWhenRealmAccessClaimIsMissingOrMalformed() {
    Jwt withoutClaim = Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("user-1")
        .build();
    Jwt malformedClaim = Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("realm_access", "not-a-map")
        .build();

    assertThat(converter.convert(withoutClaim).collectList().block()).isEmpty();
    assertThat(converter.convert(malformedClaim).collectList().block()).isEmpty();
  }
}

