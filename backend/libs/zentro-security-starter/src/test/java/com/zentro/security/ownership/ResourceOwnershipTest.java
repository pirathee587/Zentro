package com.zentro.security.ownership;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class ResourceOwnershipTest {
  @Test void comparesJwtSubjectToResourceOwner() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("customer-1").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    var authentication = new UsernamePasswordAuthenticationToken(jwt, "token");
    assertTrue(ResourceOwnership.isOwner(authentication, "customer-1"));
    assertFalse(ResourceOwnership.isOwner(authentication, "customer-2"));
  }
}
