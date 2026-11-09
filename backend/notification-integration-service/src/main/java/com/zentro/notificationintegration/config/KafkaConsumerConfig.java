package com.zentro.notificationintegration.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import com.zentro.security.observability.SafeKafkaErrorHandler;
import com.zentro.security.observability.PropagationContext;
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

  private PropagationContext propagation = PropagationContext.noop();
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setPropagation(PropagationContext propagation) { this.propagation = propagation; }

  @Bean
  DefaultErrorHandler notificationErrorHandler() {
    DefaultErrorHandler handler = new SafeKafkaErrorHandler(new FixedBackOff(5000L, FixedBackOff.UNLIMITED_ATTEMPTS), propagation);
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    return handler;
  }
}
