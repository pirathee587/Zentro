package com.zentro.gateway.config;

import com.zentro.gateway.constants.GatewayConstants;
import com.zentro.gateway.exception.ProblemWriter;
import com.zentro.gateway.security.RoleAuthorizationManager;
import com.zentro.gateway.security.RoleConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.web.cors.reactive.CorsConfigurationSource;

@Configuration
@EnableWebFluxSecurity
public class GatewaySecurityConfig {
  private static final Logger log = LoggerFactory.getLogger(GatewaySecurityConfig.class);

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
            .pathMatchers("/api/v1/tracking/mine")
                .access(RoleAuthorizationManager.hasAny(GatewayConstants.ROLE_CUSTOMER, GatewayConstants.ROLE_MERCHANT))
            .pathMatchers(HttpMethod.GET,
                "/api/v1/tracking/*", "/api/v1/tracking/*/events", "/api/v1/tracking/*/stream")
                .access(RoleAuthorizationManager.notServiceOnly())
            .pathMatchers("/api/v1/tracking/**").denyAll()
            .pathMatchers("/actuator/health/**", "/actuator/health").permitAll()
            .pathMatchers("/api/v1/admin/**", "/api/v1/dispatch/**", "/api/v1/reports/**")
                .access(RoleAuthorizationManager.has(GatewayConstants.ROLE_DISPATCHER))
            .pathMatchers("/api/v1/courier/**")
                .access(RoleAuthorizationManager.has(GatewayConstants.ROLE_COURIER))
            .pathMatchers("/api/v1/merchant/**")
                .access(RoleAuthorizationManager.has(GatewayConstants.ROLE_MERCHANT))
            .pathMatchers(HttpMethod.POST, "/api/v1/parcels")
                .access(RoleAuthorizationManager.has(GatewayConstants.ROLE_CUSTOMER))
            .pathMatchers(HttpMethod.PATCH, "/api/v1/parcels/*/status")
                .access(RoleAuthorizationManager.has(GatewayConstants.ROLE_DISPATCHER))
            .pathMatchers(HttpMethod.POST, "/api/v1/parcels/estimate", "/api/v1/parcels/estimate-fee")
                .access(RoleAuthorizationManager.hasAny(GatewayConstants.ROLE_CUSTOMER, GatewayConstants.ROLE_DISPATCHER))
            .pathMatchers("/api/v1/parcels/**").denyAll()
            .pathMatchers("/api/v1/users/**", "/api/v1/merchants/**", "/api/v1/zones/**")
                .access(RoleAuthorizationManager.humanAuthenticated())
            .pathMatchers("/actuator/prometheus")
                .access(RoleAuthorizationManager.humanAuthenticated())
            .pathMatchers("/api/v1/**").denyAll()
            .anyExchange().denyAll())
        .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(entry).accessDeniedHandler(denied)
            .jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
        .build();
  }
}
