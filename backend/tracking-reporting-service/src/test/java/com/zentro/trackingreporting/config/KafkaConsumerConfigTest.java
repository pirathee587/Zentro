package com.zentro.trackingreporting.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;

class KafkaConsumerConfigTest {

  @Test
  @DisplayName("DefaultErrorHandler is configured with bounded retries and expected settings")
  void errorHandlerConfiguration() {
    KafkaConsumerConfig config = new KafkaConsumerConfig();
    DefaultErrorHandler handler = config.trackingConsumerErrorHandler(500L, 3);

    assertThat(handler).isNotNull();
    assertThat(handler.isAckAfterHandle()).isTrue();
  }
}
