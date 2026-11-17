package com.zentro.identitypartner.user.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.security.config.KeycloakRealmRoleConverter;
import com.zentro.security.error.ProblemDetailsSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class IdentityPartnerSecurityConfig {

  @Bean
  public SecurityFilterChain identitySecurityFilterChain(
      HttpSecurity http,
      JwtDecoder decoder,
      ObjectMapper mapper) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

    return http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(registry -> registry
            .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("/api/v1/auth/**", "/error").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(ProblemDetailsSecurityHandlers.authenticationEntryPoint(mapper)))
        .exceptionHandling(errors -> errors.accessDeniedHandler(ProblemDetailsSecurityHandlers.accessDeniedHandler(mapper)))
        .build();
  }
}

