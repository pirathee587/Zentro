package com.zentro.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@Configuration
public class JwtDecoderConfig {
  @Bean
  ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                @Value("${zentro.jwt.audience}") String audience) {
    NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build();
    OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
    OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
        ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
            new org.springframework.security.oauth2.core.OAuth2Error("invalid_token", "Required audience is missing", null));
    decoder.setJwtValidator(token -> {
      OAuth2TokenValidatorResult issuerResult = issuerValidator.validate(token);
      return issuerResult.hasErrors() ? issuerResult : audienceValidator.validate(token);
    });
    return decoder;
  }
}
