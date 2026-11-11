package com.zentro.trackingreporting.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.security.config.KeycloakRealmRoleConverter;
import com.zentro.security.error.ProblemDetailsSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class TrackingSecurityConfig {

  @Bean
  SecurityFilterChain trackingSecurityFilterChain(
      HttpSecurity http,
      JwtDecoder decoder,
      ObjectMapper mapper) throws Exception {

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

    return http
        .csrf(csrf -> csrf.disable())
        .authorizeHttpRequests(registry -> registry
            .requestMatchers("/actuator/health/**", "/v3/api-docs/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/v1/tracking/mine").hasAnyRole("CUSTOMER", "MERCHANT")
            .requestMatchers(HttpMethod.GET, "/api/v1/tracking/*/stream").hasAnyRole("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER")
            .requestMatchers(HttpMethod.GET, "/api/v1/tracking/*", "/api/v1/tracking/*/events")
                .access((authentication, context) -> new AuthorizationDecision(!serviceOnly(authentication.get())))
            .requestMatchers("/graphql", "/graphql/**")
                .access((authentication, context) -> new AuthorizationDecision(!serviceOnly(authentication.get())))
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth
            .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(ProblemDetailsSecurityHandlers.authenticationEntryPoint(mapper)))
        .exceptionHandling(errors -> errors
            .accessDeniedHandler(ProblemDetailsSecurityHandlers.accessDeniedHandler(mapper)))
        .build();
  }

  private static boolean serviceOnly(Authentication authentication) {
    if (authentication == null) return false;
    boolean hasService = false;
    boolean hasHumanRole = false;
    for (GrantedAuthority authority : authentication.getAuthorities()) {
      String role = authority.getAuthority();
      if ("ROLE_SERVICE".equals(role)) hasService = true;
      if (java.util.Set.of("ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER").contains(role))
        hasHumanRole = true;
    }
    return hasService && !hasHumanRole;
  }
}

