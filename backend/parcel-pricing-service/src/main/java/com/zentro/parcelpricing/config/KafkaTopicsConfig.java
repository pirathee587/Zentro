package com.zentro.parcelpricing.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Outbox publishing setup, active only when {@code zentro.outbox.enabled=true}.
 * Creates the parcel event topics if they do not exist yet, which is convenient for local runs.
 * Production topic sizing (partitions, replication, retention) belongs to the deployment owner;
 * existing topics are never changed here.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "zentro.outbox", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OutboxProperties.class)
public class KafkaTopicsConfig {

  @Bean
  KafkaAdmin.NewTopics parcelEventTopics(OutboxProperties properties) {
    NewTopic[] topics = properties.topics().values().stream()
        .distinct()
        .sorted()
        .map(name -> TopicBuilder.name(name)
            .partitions(properties.topicPartitions())
            .replicas(properties.topicReplicas())
            .build())
        .toArray(NewTopic[]::new);
    return new KafkaAdmin.NewTopics(topics);
  }
}
