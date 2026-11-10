package com.zentro.security.observability;

import io.micrometer.tracing.Span;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.BackOff;

/** Same retry/seek/recovery semantics, with no payload or exception-chain logging. */
public final class SafeKafkaErrorHandler extends DefaultErrorHandler {
  private static final Logger log = LoggerFactory.getLogger(SafeKafkaErrorHandler.class);
  public SafeKafkaErrorHandler(BackOff backOff, PropagationContext propagation) {
    super((record, exception) -> {
      try (var span = propagation.start(KafkaPropagationInterceptor.metadata(record.headers()), "kafka.recovery", Span.Kind.CONSUMER);
           var scope = span.scope()) {
        span.failed();
        log.error("Kafka record exhausted retries: category=processing_error partition={} offset={}", record.partition(), record.offset());
      }
    }, backOff);
    setRetryListeners((record, exception, attempt) -> {
      try (var span = propagation.start(KafkaPropagationInterceptor.metadata(record.headers()), "kafka.retry", Span.Kind.CONSUMER);
           var scope = span.scope()) {
        span.failed();
        log.warn("Kafka record failed: category=processing_error partition={} offset={} attempt={}", record.partition(), record.offset(), attempt);
      }
    });
  }
  @Override public void handleRemaining(Exception exception, List<ConsumerRecord<?, ?>> records,
      Consumer<?, ?> consumer, MessageListenerContainer container) {
    try {
      super.handleRemaining(exception, records, consumer, container);
    } catch (KafkaException scheduled) {
      // The delegate has already classified the original cause and performed the same seeks/backoff.
      // The container logs this signal; never attach the payload-bearing original exception to it.
      throw new KafkaException("Kafka record retry scheduled", KafkaException.Level.WARN, null);
    }
  }
  @Override public void handleOtherException(Exception exception, Consumer<?, ?> consumer,
      MessageListenerContainer container, boolean batchListener) {
    // DefaultErrorHandler also rejects record-less failures with IllegalStateException.
    // Keep that behavior without giving the container a sensitive exception chain to log.
    throw new IllegalStateException("Kafka consumer failed before record context was available");
  }
}
