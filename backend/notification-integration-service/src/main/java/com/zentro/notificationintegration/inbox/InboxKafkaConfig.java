package com.zentro.notificationintegration.inbox;

import com.zentro.security.observability.PropagationContext;
import com.zentro.security.observability.SafeKafkaErrorHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.util.backoff.FixedBackOff;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="zentro.inbox.consumer.enabled",havingValue="true")
public class InboxKafkaConfig {
  @Bean("inboxListenerContainerFactory")
  ConcurrentKafkaListenerContainerFactory<Object,Object> inboxFactory(
      ConcurrentKafkaListenerContainerFactoryConfigurer configurer,ConsumerFactory<Object,Object> consumerFactory) {
    var factory=new ConcurrentKafkaListenerContainerFactory<Object,Object>();
    configurer.configure(factory,consumerFactory);
    var handler=new SafeKafkaErrorHandler(new FixedBackOff(5000L,FixedBackOff.UNLIMITED_ATTEMPTS),PropagationContext.noop());
    handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
    factory.setCommonErrorHandler(handler);
    return factory;
  }
}
