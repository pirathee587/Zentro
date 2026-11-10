package com.zentro.gateway.security;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

public final class JwtTokenValidator implements OAuth2TokenValidator<Jwt> {
  private static final Logger log = LoggerFactory.getLogger(JwtTokenValidator.class);

  private final String expectedIssuer;
  private final String requiredAudience;
  private final OAuth2TokenValidator<Jwt> issuerValidator;

  public JwtTokenValidator(String expectedIssuer, String requiredAudience) {
    this.expectedIssuer = Objects.requireNonNull(expectedIssuer, "expectedIssuer must not be null");
    this.requiredAudience = Objects.requireNonNull(requiredAudience, "requiredAudience must not be null");
    this.issuerValidator = JwtValidators.createDefaultWithIssuer(expectedIssuer);
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    OAuth2TokenValidatorResult issuerResult = issuerValidator.validate(token);
    if (issuerResult.hasErrors()) {
      log.warn("JWT validation failed: category=issuer_or_time");
      return issuerResult;
    }

    List<String> audiences = token.getAudience();
    if (audiences != null && audiences.contains(requiredAudience)) {
      return OAuth2TokenValidatorResult.success();
    }

    log.warn("JWT validation failed: category=audience");
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "Required audience is missing", null));
  }
}

