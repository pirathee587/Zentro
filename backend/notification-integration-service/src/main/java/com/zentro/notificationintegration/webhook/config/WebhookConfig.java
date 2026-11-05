package com.zentro.notificationintegration.webhook.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Always-on webhook setup: settings binding. The registration API works even when delivery is disabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {
}
