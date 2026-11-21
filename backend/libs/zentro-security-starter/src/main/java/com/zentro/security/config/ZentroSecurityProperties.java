package com.zentro.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zentro.security")
public record ZentroSecurityProperties(String issuerUri, String audience, String jwksUri, java.util.List<String> publicGetPaths) {
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public ZentroSecurityProperties {
    publicGetPaths = publicGetPaths == null ? java.util.List.of() : java.util.List.copyOf(publicGetPaths);
  }
  public ZentroSecurityProperties(String issuerUri, String audience, String jwksUri) { this(issuerUri,audience,jwksUri,java.util.List.of()); }
}
