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
            .requestMatchers(HttpMethod.GET, "/api/v1/tracking/*", "/api/v1/tracking/*/events", "/api/v1/tracking/*/stream").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth
            .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(ProblemDetailsSecurityHandlers.authenticationEntryPoint(mapper)))
        .exceptionHandling(errors -> errors
            .accessDeniedHandler(ProblemDetailsSecurityHandlers.accessDeniedHandler(mapper)))
        .build();
  }
}

