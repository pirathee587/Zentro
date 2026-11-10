package com.zentro.security.observability;

import static org.assertj.core.api.Assertions.assertThat;
import com.zentro.security.config.KafkaPropagationAutoConfiguration;
import com.zentro.security.config.PropagationAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaPropagationFactoriesTest {
  @Test void defaultAndSeparateWebhookFactoriesAreBothInstrumented() {
    new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
        PropagationAutoConfiguration.class, KafkaPropagationAutoConfiguration.class))
        .withBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class, ConcurrentKafkaListenerContainerFactory::new)
        .withBean("webhookListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class, ConcurrentKafkaListenerContainerFactory::new)
        .run(context -> {
          assertThat(context).hasNotFailed();
          for (String name : new String[] {"kafkaListenerContainerFactory", "webhookListenerContainerFactory"}) {
            var factory = context.getBean(name, ConcurrentKafkaListenerContainerFactory.class);
            assertThat(ReflectionTestUtils.getField(factory, "recordInterceptor")).isInstanceOf(KafkaPropagationInterceptor.class);
            assertThat(factory.getContainerProperties().isObservationEnabled()).isFalse();
          }
        });
  }
}
