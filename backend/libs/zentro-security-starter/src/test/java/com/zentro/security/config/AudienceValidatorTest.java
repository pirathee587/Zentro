package com.zentro.security.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {
  @Test void acceptsConfiguredAudience() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-1")
        .audience(java.util.List.of("zentro-api")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    assertTrue(new AudienceValidator("zentro-api").validate(jwt).hasErrors() == false);
  }
  @Test void rejectsUnexpectedAudience() {
    Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("user-1")
        .audience(java.util.List.of("another-api")).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    assertFalse(new AudienceValidator("zentro-api").validate(jwt).getErrors().isEmpty());
  }
}
