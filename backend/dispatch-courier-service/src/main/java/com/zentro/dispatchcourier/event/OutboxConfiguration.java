package com.zentro.dispatchcourier.event;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "zentro.outbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfiguration {
  @Bean
  KafkaAdmin.NewTopics assignmentTopics(OutboxProperties properties) {
    NewTopic[] topics = properties.topics().values().stream().distinct().sorted()
        .map(name -> TopicBuilder.name(name).partitions(3).replicas(1).build())
        .toArray(NewTopic[]::new);
    return new KafkaAdmin.NewTopics(topics);
  }
}
