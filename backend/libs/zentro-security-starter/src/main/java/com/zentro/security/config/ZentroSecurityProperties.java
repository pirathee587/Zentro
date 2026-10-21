package com.zentro.security.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "zentro.security")
public record ZentroSecurityProperties(String issuerUri, String audience) { }
