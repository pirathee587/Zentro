package com.zentro.dispatchcourier.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.security.config.KeycloakRealmRoleConverter;
import com.zentro.security.error.ProblemDetailsSecurityHandlers;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Zero Trust HTTP security configuration for S3 (Dispatch & Courier Service).
 *
 * <p>Reuses {@link JwtDecoder}, {@link KeycloakRealmRoleConverter}, and
 * {@link ProblemDetailsSecurityHandlers} from {@code zentro-security-starter} while enforcing
 * S3-specific endpoint role boundaries at the HTTP filter chain level in addition to
 * controller-level {@code @PreAuthorize} annotations.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class DispatchCourierSecurityConfig {

  @Bean
  public SecurityFilterChain dispatchCourierSecurityFilterChain(
      HttpSecurity http,
      JwtDecoder decoder,
      ObjectMapper mapper) throws Exception {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());

    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(registry -> registry
            .requestMatchers("/actuator/health", "/actuator/health/**", "/v3/api-docs/**").permitAll()
            .requestMatchers("/api/v1/dispatch/**").hasRole("DISPATCHER")
            .requestMatchers("/api/v1/courier/deliveries/**").hasRole("COURIER")
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth -> oauth
            .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(ProblemDetailsSecurityHandlers.authenticationEntryPoint(mapper)))
        .exceptionHandling(errors -> errors
            .accessDeniedHandler(ProblemDetailsSecurityHandlers.accessDeniedHandler(mapper)))
        .build();
  }
}

