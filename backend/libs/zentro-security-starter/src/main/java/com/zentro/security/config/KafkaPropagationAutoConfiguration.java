package com.zentro.security.config;

import com.zentro.security.observability.KafkaPropagationInterceptor;
import com.zentro.security.observability.PropagationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;

@AutoConfiguration(after = PropagationAutoConfiguration.class)
@ConditionalOnClass(name = "org.springframework.kafka.config.AbstractKafkaListenerContainerFactory")
public class KafkaPropagationAutoConfiguration {
  @Bean static BeanPostProcessor kafkaPropagationFactories(ObjectProvider<PropagationContext> propagation) {
    return new BeanPostProcessor() {
      @Override @SuppressWarnings({"rawtypes", "unchecked"})
      public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
          ((AbstractKafkaListenerContainerFactory) factory).setRecordInterceptor(new KafkaPropagationInterceptor(propagation.getObject()));
          // This interceptor owns Kafka attempt spans; do not also enable container observation spans.
          factory.getContainerProperties().setObservationEnabled(false);
        }
        return bean;
      }
    };
  }
}
