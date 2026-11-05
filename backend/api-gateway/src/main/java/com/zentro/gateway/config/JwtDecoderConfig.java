package com.zentro.gateway.config;

import com.zentro.gateway.security.JwtTokenValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.util.StringUtils;

@Configuration
public class JwtDecoderConfig {
  @Bean
  ReactiveJwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
                                @Value("${zentro.jwt.audience}") String audience,
                                @Value("${zentro.jwt.jwks-uri:}") String jwksUri) {
    NimbusReactiveJwtDecoder decoder = StringUtils.hasText(jwksUri)
        ? NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build()
        : NimbusReactiveJwtDecoder.withIssuerLocation(issuer).build();
    decoder.setJwtValidator(new JwtTokenValidator(issuer, audience));
    return decoder;
  }
}
