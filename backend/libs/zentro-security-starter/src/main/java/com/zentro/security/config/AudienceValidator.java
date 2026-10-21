package com.zentro.security.config;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects tokens not minted for the Zentro API audience. */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
  private final String audience;
  public AudienceValidator(String audience) { this.audience = audience; }
  @Override public OAuth2TokenValidatorResult validate(Jwt jwt) {
    List<String> audiences = jwt.getAudience();
    return audiences.contains(audience)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, "Required audience is missing", null));
  }
}
