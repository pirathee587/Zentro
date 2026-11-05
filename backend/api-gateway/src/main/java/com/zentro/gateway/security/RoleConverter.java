package com.zentro.gateway.security;

import com.zentro.gateway.constants.GatewayConstants;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import reactor.core.publisher.Flux;

public final class RoleConverter implements Converter<Jwt, Flux<GrantedAuthority>> {
  @Override
  public Flux<GrantedAuthority> convert(Jwt jwt) {
    Object claim = jwt.getClaim("realm_access");
    Object rolesObject = claim instanceof Map<?, ?> realm ? realm.get("roles") : null;
    List<SimpleGrantedAuthority> authorities = rolesObject instanceof Collection<?> roles
        ? roles.stream().filter(String.class::isInstance).map(String.class::cast)
            .filter(GatewayConstants.ALLOWED_REALM_ROLES::contains)
            .map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList()
        : List.of();
    return Flux.fromIterable(authorities);
  }
}

