package com.zentro.notificationintegration.webhook.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/** Always-on webhook setup: settings binding. The registration API works even when delivery is disabled. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookConfig {
  public static final String LISTENER_CONTAINER_FACTORY = "webhookListenerContainerFactory";

  /**
   * Delivery setup, active only when {@code zentro.webhooks.enabled=true}. The webhook listener has its own
   * container factory and error handler, so it works the same whether notifications are enabled or not: a
   * message that could not be stored (for example the database is down) is redelivered every 5 seconds without a
   * limit instead of being skipped after 10 tries.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(prefix = "zentro.webhooks", name = "enabled", havingValue = "true")
  @EnableScheduling
  static class Delivery {
    private static final Logger log = LoggerFactory.getLogger(Delivery.class);

    @Bean(LISTENER_CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<Object, Object> webhookListenerContainerFactory(
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
        ObjectProvider<ConsumerFactory<Object, Object>> consumerFactory) {
      ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
      configurer.configure(factory, consumerFactory.getObject());
      DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
      handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
      handler.setRetryListeners((record, ex, attempt) -> log.warn(
          "Parcel event at {}-{}@{} not stored for webhooks yet (attempt {}): {}; will be redelivered",
          record.topic(), record.partition(), record.offset(), attempt, ex.getClass().getSimpleName()));
      factory.setCommonErrorHandler(handler);
      return factory;
    }
  }
}
