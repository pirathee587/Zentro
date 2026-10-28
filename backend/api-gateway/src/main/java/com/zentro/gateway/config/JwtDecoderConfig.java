package com.zentro.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@Configuration
public class JwtDecoderConfig {
  private static final Logger log = LoggerFactory.getLogger(JwtDecoderConfig.class);

  @Bean
  ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                @Value("${zentro.jwt.audience}") String audience,
                                @Value("${zentro.jwt.jwks-uri:}") String jwksUri) {
    NimbusReactiveJwtDecoder decoder = StringUtils.hasText(jwksUri)
        ? NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build()
        : NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build();
    OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
    OAuth2TokenValidator<Jwt> audienceValidator = jwt -> {
      if (jwt.getAudience().contains(audience)) return OAuth2TokenValidatorResult.success();
      log.warn("JWT audience validation failed: issuer={} audiences={} requiredAudience={}",
          jwt.getIssuer(), jwt.getAudience(), audience);
      return OAuth2TokenValidatorResult.failure(
          new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Required audience is missing", null));
    };
    decoder.setJwtValidator(token -> {
      OAuth2TokenValidatorResult issuerResult = issuerValidator.validate(token);
      if (issuerResult.hasErrors()) {
        log.warn("JWT issuer/time validation failed: issuer={} expectedIssuer={} expiresAt={} errors={}",
            token.getIssuer(), issuer, token.getExpiresAt(),
            issuerResult.getErrors().stream().map(error -> error.getErrorCode()).toList());
      }
      return issuerResult.hasErrors() ? issuerResult : audienceValidator.validate(token);
    });
    return decoder;
  }
}
