package com.zentro.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.security.error.ProblemDetailsSecurityHandlers;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@AutoConfiguration
@EnableMethodSecurity
@EnableConfigurationProperties(ZentroSecurityProperties.class)
public class ZentroSecurityAutoConfiguration {
  @Bean JwtDecoder zentroJwtDecoder(ZentroSecurityProperties properties) {
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(properties.issuerUri());
    decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuerUri()));
    decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(properties.issuerUri()), new AudienceValidator(properties.audience())));
    return decoder;
  }
  @Bean SecurityFilterChain zentroSecurityFilterChain(HttpSecurity http, JwtDecoder decoder, ObjectMapper mapper) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
    return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(registry -> registry
        .requestMatchers("/actuator/health", "/v3/api-docs/**").permitAll().anyRequest().authenticated())
      .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
        .authenticationEntryPoint(ProblemDetailsSecurityHandlers.authenticationEntryPoint(mapper)))
      .exceptionHandling(errors -> errors.accessDeniedHandler(ProblemDetailsSecurityHandlers.accessDeniedHandler(mapper))).build();
  }
}
