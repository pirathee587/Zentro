package com.zentro.trackingreporting.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import com.zentro.security.observability.SafeKafkaErrorHandler;
import com.zentro.security.observability.PropagationContext;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka consumer error handling configuration for tracking-reporting-service.
 *
 * <p>Configures a {@link DefaultErrorHandler} for the Kafka message listener container:
 * <ul>
 *   <li>Fatal / non-retryable exceptions (such as {@link JsonProcessingException} and {@link IllegalArgumentException})
 *       are not retried and do not block partition processing.</li>
 *   <li>Database and transaction failures retry without exhaustion, preserving the record until recovery.</li>
 *   <li>Other unexpected processing failures use the configured bounded backoff.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "zentro.kafka", name = "enabled", havingValue = "true")
public class KafkaConsumerConfig {

  private PropagationContext propagation = PropagationContext.noop();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPropagation(PropagationContext propagation) { this.propagation = propagation; }

  @Bean
  DefaultErrorHandler trackingConsumerErrorHandler(
      @Value("${zentro.kafka.consumer.retry.interval-ms:1000}") long intervalMs,
      @Value("${zentro.kafka.consumer.retry.max-attempts:3}") int maxAttempts) {
    long attempts = Math.max(1, maxAttempts);
    FixedBackOff backOff = new FixedBackOff(intervalMs, attempts - 1);
    DefaultErrorHandler handler = new SafeKafkaErrorHandler(backOff, propagation);
    // Exhausting a transient database retry must not advance the offset and lose a business fact.
    handler.setBackOffFunction((record, exception) -> {
      for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
        if (cause instanceof org.springframework.dao.DataAccessException
            || cause instanceof org.springframework.transaction.TransactionException) {
          return new FixedBackOff(intervalMs, FixedBackOff.UNLIMITED_ATTEMPTS);
        }
      }
      return backOff;
    });
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    handler.addNotRetryableExceptions(
        JsonProcessingException.class,
        IllegalArgumentException.class
    );
    return handler;
  }
}

