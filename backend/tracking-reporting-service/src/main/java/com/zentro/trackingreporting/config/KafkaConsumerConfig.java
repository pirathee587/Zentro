package com.zentro.trackingreporting.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer error handling configuration for tracking-reporting-service.
 *
 * <p>Configures a {@link DefaultErrorHandler} for the Kafka message listener container:
 * <ul>
 *   <li>Fatal / non-retryable exceptions (such as {@link JsonProcessingException} and {@link IllegalArgumentException})
 *       are not retried and do not block partition processing.</li>
 *   <li>Transient processing exceptions (such as database access failures) are retried with configured
 *       backoff before exhausting retries.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "zentro.kafka", name = "enabled", havingValue = "true")
public class KafkaConsumerConfig {

  private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

  @Bean
  DefaultErrorHandler trackingConsumerErrorHandler(
      @Value("${zentro.kafka.consumer.retry.interval-ms:1000}") long intervalMs,
      @Value("${zentro.kafka.consumer.retry.max-attempts:3}") int maxAttempts) {
    long attempts = Math.max(1, maxAttempts);
    FixedBackOff backOff = new FixedBackOff(intervalMs, attempts - 1);
    DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    handler.addNotRetryableExceptions(
        JsonProcessingException.class,
        IllegalArgumentException.class
    );
    handler.setRetryListeners((record, ex, attempt) -> log.warn(
        "Tracking parcel event at {}-{}@{} failed processing (attempt {}): {}; will retry",
        record.topic(), record.partition(), record.offset(), attempt, ex.getClass().getSimpleName()));
    return handler;
  }
}

