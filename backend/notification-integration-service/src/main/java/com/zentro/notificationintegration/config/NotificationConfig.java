package com.zentro.notificationintegration.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Always-on notification setup: settings binding and the clock used for retry and health times. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

  @Bean
  Clock notificationClock() {
    return Clock.systemUTC();
  }
}
