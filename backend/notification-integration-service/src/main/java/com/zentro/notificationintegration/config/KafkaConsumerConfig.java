package com.zentro.notificationintegration.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Consumer-side setup, active only when {@code zentro.notifications.consumer.enabled=true}.
 *
 * <p>A message is acknowledged only after it was stored. If storing fails (for example the database is down),
 * the listener throws and this error handler re-delivers the same record every 5 seconds without a limit. The
 * framework default would give up after 10 tries and commit the offset, silently losing the event. Malformed
 * messages never reach this handler: the listener records and acknowledges them itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "zentro.notifications.consumer", name = "enabled", havingValue = "true")
@EnableScheduling
public class KafkaConsumerConfig {
  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

  @Bean
  DefaultErrorHandler notificationErrorHandler() {
    DefaultErrorHandler handler = new DefaultErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    handler.setRetryListeners((record, ex, attempt) -> log.warn(
        "Parcel event at {}-{}@{} not stored yet (attempt {}): {}; will be redelivered",
        record.topic(), record.partition(), record.offset(), attempt, ex.getClass().getSimpleName()));
    return handler;
  }
}
