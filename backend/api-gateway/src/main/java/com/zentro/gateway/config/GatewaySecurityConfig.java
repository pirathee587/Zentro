package com.zentro.gateway.config;

import com.zentro.gateway.error.ProblemWriter;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {
  private static final Logger log = LoggerFactory.getLogger(GatewaySecurityConfig.class);
  private static final Set<String> HUMAN = Set.of("ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER");

  @Bean
  SecurityWebFilterChain gatewaySecurityFilterChain(ServerHttpSecurity http, CorsConfigurationSource corsSource) {
    ReactiveJwtAuthenticationConverter converter = new ReactiveJwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new RoleConverter());
    ServerAuthenticationEntryPoint entry = (exchange, ex) -> {
      // Never log the Authorization header, token, or exception message.
      String reason = ex instanceof OAuth2AuthenticationException oauth
          ? oauth.getError().getErrorCode() : ex.getClass().getSimpleName();
      log.warn("JWT authentication rejected: path={} exception={} reason={}",
          exchange.getRequest().getPath().value(), ex.getClass().getSimpleName(), reason);
      return ProblemWriter.write(exchange.getResponse(), HttpStatus.UNAUTHORIZED,
          "Unauthorized", "Authentication is required or the bearer token is invalid.", exchange.getRequest().getPath().value());
    };
    ServerAccessDeniedHandler denied = (exchange, ex) -> ProblemWriter.write(exchange.getResponse(), HttpStatus.FORBIDDEN,
        "Forbidden", "You do not have access to this resource.", exchange.getRequest().getPath().value());
    return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
        .cors(corsSpec -> corsSpec.configurationSource(corsSource))
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .logout(ServerHttpSecurity.LogoutSpec::disable)
        .exceptionHandling(spec -> spec.authenticationEntryPoint(entry).accessDeniedHandler(denied))
        .authorizeExchange(exchanges -> exchanges
            .pathMatchers("/api/v1/tracking/mine").access(hasAny("ROLE_CUSTOMER", "ROLE_MERCHANT"))
            .pathMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/tracking/**").access(notServiceOnly())
            .pathMatchers("/actuator/health/**", "/actuator/health").permitAll()
            .pathMatchers("/api/v1/admin/**", "/api/v1/dispatch/**", "/api/v1/reports/**").access(has("ROLE_DISPATCHER"))
            .pathMatchers("/api/v1/courier/**").access(has("ROLE_COURIER"))
            .pathMatchers("/api/v1/merchant/**").access(has("ROLE_MERCHANT"))
            .pathMatchers("/api/v1/parcels/**").access(hasAny("ROLE_CUSTOMER", "ROLE_DISPATCHER"))
            .pathMatchers("/api/v1/users/**", "/api/v1/merchants/**", "/api/v1/zones/**").access(humanAuthenticated())
            .pathMatchers("/actuator/prometheus").access(humanAuthenticated())
            .pathMatchers("/api/v1/**").denyAll()
            .anyExchange().permitAll())
        .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(entry).accessDeniedHandler(denied)
            .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
  }

  private static org.springframework.security.authorization.ReactiveAuthorizationManager<org.springframework.security.web.server.authorization.AuthorizationContext> has(String role) {
    return (authentication, context) -> authentication.map(auth -> new AuthorizationDecision(isHuman(auth) && auth.getAuthorities().stream().anyMatch(a -> role.equals(a.getAuthority())))).defaultIfEmpty(new AuthorizationDecision(false));
  }

  private static org.springframework.security.authorization.ReactiveAuthorizationManager<org.springframework.security.web.server.authorization.AuthorizationContext> hasAny(String... roles) {
    return (authentication, context) -> authentication.map(auth -> new AuthorizationDecision(isHuman(auth) && auth.getAuthorities().stream().anyMatch(a -> java.util.Arrays.asList(roles).contains(a.getAuthority())))).defaultIfEmpty(new AuthorizationDecision(false));
  }

  private static org.springframework.security.authorization.ReactiveAuthorizationManager<org.springframework.security.web.server.authorization.AuthorizationContext> humanAuthenticated() {
    return (authentication, context) -> authentication.map(auth -> new AuthorizationDecision(auth.isAuthenticated() && !serviceOnly(auth))).defaultIfEmpty(new AuthorizationDecision(false));
  }

  private static org.springframework.security.authorization.ReactiveAuthorizationManager<org.springframework.security.web.server.authorization.AuthorizationContext> notServiceOnly() {
    return (authentication, context) -> authentication.map(auth -> new AuthorizationDecision(!serviceOnly(auth))).defaultIfEmpty(new AuthorizationDecision(true));
  }

  private static boolean serviceOnly(Authentication auth) {
    return auth.getAuthorities().stream().map(org.springframework.security.core.GrantedAuthority::getAuthority)
        .filter(a -> a.startsWith("ROLE_")).allMatch("ROLE_SERVICE"::equals)
        && auth.getAuthorities().stream().anyMatch(a -> "ROLE_SERVICE".equals(a.getAuthority()));
  }

  private static boolean isHuman(Authentication auth) {
    return auth.getAuthorities().stream().anyMatch(a -> HUMAN.contains(a.getAuthority()));
  }
}
