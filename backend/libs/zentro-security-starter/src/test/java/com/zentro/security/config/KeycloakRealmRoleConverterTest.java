package com.zentro.security.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {
  @Test void mapsRealmRolesToSpringAuthorities() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-1")
        .claim("realm_access", Map.of("roles", List.of("customer", "MERCHANT")))
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    assertEquals(List.of("ROLE_CUSTOMER", "ROLE_MERCHANT"), new KeycloakRealmRoleConverter().convert(jwt).stream().map(a -> a.getAuthority()).toList());
  }
}
