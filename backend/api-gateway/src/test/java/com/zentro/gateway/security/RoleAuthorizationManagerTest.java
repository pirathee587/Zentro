package com.zentro.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import reactor.core.publisher.Mono;

class RoleAuthorizationManagerTest {
  @Test
  void hasAndHasAnyGrantAccessOnlyToMatchingHumanRoles() {
    Authentication customer = auth("ROLE_CUSTOMER");
    Authentication service = auth("ROLE_SERVICE");

    AuthorizationResult customerHasCustomer = RoleAuthorizationManager.has("ROLE_CUSTOMER")
        .authorize(Mono.just(customer), null).block();
    AuthorizationResult customerHasDispatcher = RoleAuthorizationManager.has("ROLE_DISPATCHER")
        .authorize(Mono.just(customer), null).block();
    AuthorizationResult customerHasAny = RoleAuthorizationManager.hasAny("ROLE_CUSTOMER", "ROLE_DISPATCHER")
        .authorize(Mono.just(customer), null).block();
    AuthorizationResult serviceHasService = RoleAuthorizationManager.has("ROLE_SERVICE")
        .authorize(Mono.just(service), null).block();

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

    assertThat(RoleAuthorizationManager.humanAuthenticated().authorize(Mono.just(courier), null).block().isGranted()).isTrue();
    assertThat(RoleAuthorizationManager.humanAuthenticated().authorize(Mono.just(serviceOnly), null).block().isGranted()).isFalse();
    assertThat(RoleAuthorizationManager.humanAuthenticated().authorize(Mono.just(roleless), null).block().isGranted()).isFalse();
    assertThat(RoleAuthorizationManager.humanAuthenticated().authorize(Mono.empty(), null).block().isGranted()).isFalse();
  }

  @Test
  void notServiceOnlyPermitsAnonymousAndHumanCallersButDeniesServiceOnlyTokens() {
    Authentication customer = auth("ROLE_CUSTOMER");
    Authentication serviceOnly = auth("ROLE_SERVICE");

    assertThat(RoleAuthorizationManager.notServiceOnly().authorize(Mono.empty(), null).block().isGranted()).isTrue();
    assertThat(RoleAuthorizationManager.notServiceOnly().authorize(Mono.just(customer), null).block().isGranted()).isTrue();
    assertThat(RoleAuthorizationManager.notServiceOnly().authorize(Mono.just(serviceOnly), null).block().isGranted()).isFalse();
  }

  @Test
  void hasAnyIncludingServiceAdmitsListedRolesOnly() {
    var manager = RoleAuthorizationManager.hasAnyIncludingService("ROLE_CUSTOMER", "ROLE_SERVICE");
    assertThat(manager.authorize(Mono.just(auth("ROLE_SERVICE")), null).block().isGranted()).isTrue();
    assertThat(manager.authorize(Mono.just(auth("ROLE_CUSTOMER")), null).block().isGranted()).isTrue();
    assertThat(manager.authorize(Mono.just(auth("ROLE_COURIER")), null).block().isGranted()).isFalse();
    assertThat(manager.authorize(Mono.just(auth()), null).block().isGranted()).isFalse();
    assertThat(manager.authorize(Mono.empty(), null).block().isGranted()).isFalse();
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

