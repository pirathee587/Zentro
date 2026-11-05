package com.zentro.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

class RoleAuthorizationManagerTest {
  @Test
  void hasAndHasAnyGrantAccessOnlyToMatchingHumanRoles() {
    Authentication customer = auth("ROLE_CUSTOMER");
    Authentication service = auth("ROLE_SERVICE");

    AuthorizationDecision customerHasCustomer = RoleAuthorizationManager.has("ROLE_CUSTOMER")
        .check(Mono.just(customer), null).block();
    AuthorizationDecision customerHasDispatcher = RoleAuthorizationManager.has("ROLE_DISPATCHER")
        .check(Mono.just(customer), null).block();
    AuthorizationDecision customerHasAny = RoleAuthorizationManager.hasAny("ROLE_CUSTOMER", "ROLE_DISPATCHER")
        .check(Mono.just(customer), null).block();
    AuthorizationDecision serviceHasService = RoleAuthorizationManager.has("ROLE_SERVICE")
        .check(Mono.just(service), null).block();

    assertThat(customerHasCustomer.isGranted()).isTrue();
    assertThat(customerHasDispatcher.isGranted()).isFalse();
    assertThat(customerHasAny.isGranted()).isTrue();
    assertThat(serviceHasService.isGranted()).isFalse();
  }

  @Test
  void humanAuthenticatedRejectsServiceOnlyAndRolelessAndAnonymousCallers() {
    Authentication courier = auth("ROLE_COURIER");
    Authentication serviceOnly = auth("ROLE_SERVICE");
    Authentication roleless = auth();

    assertThat(RoleAuthorizationManager.humanAuthenticated().check(Mono.just(courier), null).block().isGranted()).isTrue();
    assertThat(RoleAuthorizationManager.humanAuthenticated().check(Mono.just(serviceOnly), null).block().isGranted()).isFalse();
    assertThat(RoleAuthorizationManager.humanAuthenticated().check(Mono.just(roleless), null).block().isGranted()).isFalse();
    assertThat(RoleAuthorizationManager.humanAuthenticated().check(Mono.empty(), null).block().isGranted()).isFalse();
  }

  @Test
  void notServiceOnlyPermitsAnonymousAndHumanCallersButDeniesServiceOnlyTokens() {
    Authentication customer = auth("ROLE_CUSTOMER");
    Authentication serviceOnly = auth("ROLE_SERVICE");

    assertThat(RoleAuthorizationManager.notServiceOnly().check(Mono.empty(), null).block().isGranted()).isTrue();
    assertThat(RoleAuthorizationManager.notServiceOnly().check(Mono.just(customer), null).block().isGranted()).isTrue();
    assertThat(RoleAuthorizationManager.notServiceOnly().check(Mono.just(serviceOnly), null).block().isGranted()).isFalse();
  }

  private static Authentication auth(String... roles) {
    List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
        .map(SimpleGrantedAuthority::new)
        .toList();
    TestingAuthenticationToken token = new TestingAuthenticationToken("user", "n/a", authorities);
    token.setAuthenticated(true);
    return token;
  }
}

