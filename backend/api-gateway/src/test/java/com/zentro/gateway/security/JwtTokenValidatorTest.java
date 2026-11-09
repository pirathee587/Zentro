package com.zentro.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtTokenValidatorTest {
  private static final String ISSUER = "http://localhost:8180/realms/zentro";
  private static final String AUDIENCE = "zentro-api";

  private final JwtTokenValidator validator = new JwtTokenValidator(ISSUER, AUDIENCE);

  @Test
  void acceptsTokenWithMatchingIssuerValidTimestampsAndRequiredAudience() {
    Jwt jwt = Jwt.withTokenValue("valid-token")
        .header("alg", "RS256")
        .issuer(ISSUER)
        .audience(List.of(AUDIENCE))
        .issuedAt(Instant.now().minusSeconds(30))
        .expiresAt(Instant.now().plusSeconds(300))
        .build();

    OAuth2TokenValidatorResult result = validator.validate(jwt);

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void rejectsTokenWithMismatchedIssuer() {
    Jwt jwt = Jwt.withTokenValue("wrong-issuer")
        .header("alg", "RS256")
        .issuer("http://evil.example/realms/zentro")
        .audience(List.of(AUDIENCE))
        .issuedAt(Instant.now().minusSeconds(30))
        .expiresAt(Instant.now().plusSeconds(300))
        .build();

    OAuth2TokenValidatorResult result = validator.validate(jwt);

    assertThat(result.hasErrors()).isTrue();
  }

  @Test
  void rejectsExpiredToken() {
    Jwt jwt = Jwt.withTokenValue("expired-token")
        .header("alg", "RS256")
        .issuer(ISSUER)
        .audience(List.of(AUDIENCE))
        .issuedAt(Instant.now().minusSeconds(600))
        .expiresAt(Instant.now().minusSeconds(120))
        .build();

    OAuth2TokenValidatorResult result = validator.validate(jwt);

    assertThat(result.hasErrors()).isTrue();
  }

  @Test
  void rejectsTokenMissingRequiredAudience() {
    Jwt jwt = Jwt.withTokenValue("wrong-audience")
        .header("alg", "RS256")
        .issuer(ISSUER)
        .audience(List.of("other-client"))
        .issuedAt(Instant.now().minusSeconds(30))
        .expiresAt(Instant.now().plusSeconds(300))
        .build();

    OAuth2TokenValidatorResult result = validator.validate(jwt);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).extracting("description")
        .contains("Required audience is missing");
  }

  @Test
  void rejectsTokenWithEmptyAudience() {
    Jwt jwt = Jwt.withTokenValue("empty-audience")
        .header("alg", "RS256")
        .issuer(ISSUER)
        .audience(List.of())
        .issuedAt(Instant.now().minusSeconds(30))
        .expiresAt(Instant.now().plusSeconds(300))
        .build();

    OAuth2TokenValidatorResult result = validator.validate(jwt);

    assertThat(result.hasErrors()).isTrue();
    assertThat(result.getErrors()).extracting("description")
        .contains("Required audience is missing");
  }
}

