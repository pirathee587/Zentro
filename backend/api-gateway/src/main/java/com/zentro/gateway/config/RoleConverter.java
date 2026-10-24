package com.zentro.gateway.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;

public final class RoleConverter implements Converter<Jwt, Flux<GrantedAuthority>> {
  private static final Set<String> ALLOWED = Set.of("CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER", "SERVICE");

  @Override
  public Flux<GrantedAuthority> convert(Jwt jwt) {
    Object claim = jwt.getClaim("realm_access");
    Object rolesObject = claim instanceof Map<?, ?> realm ? realm.get("roles") : null;
    List<SimpleGrantedAuthority> authorities = rolesObject instanceof Collection<?> roles
        ? roles.stream().filter(String.class::isInstance).map(String.class::cast)
            .filter(ALLOWED::contains).map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()
        : List.of();
    return Flux.fromIterable(authorities);
  }
}
