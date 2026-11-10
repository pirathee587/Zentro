package com.zentro.dispatchcourier.event;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "zentro.outbox", name = "enabled", havingValue = "true")
@EnableScheduling
public class OutboxPublisher {
  private final OutboxRepository repository;
  private final KafkaTemplate<String, String> kafka;
  private final OutboxProperties properties;
  private final TransactionTemplate transactions;

  public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
      OutboxProperties properties, org.springframework.transaction.PlatformTransactionManager manager) {
    this.repository = repository;
    this.kafka = kafka;
    this.properties = properties;
    this.transactions = new TransactionTemplate(manager);
  }

  @Scheduled(fixedDelayString = "${zentro.outbox.poll-interval-ms:1000}")
  public void publishPending() {
    transactions.executeWithoutResult(status -> {
      for (var event : repository.fetchBatch(properties.maxAttempts(), properties.batchSize())) {
        String topic = properties.topicFor(event.eventType());
        if (topic == null) {
          repository.markFailed(event.eventId(), "No topic configured for event type", OffsetDateTime.now().plus(properties.retryDelay(event.attempts())));
          return;
        }
        try {
          ProducerRecord<String, String> record =
              new ProducerRecord<>(topic, event.aggregateId().toString(), event.payload());
          record.headers().add("eventId", event.eventId().toString().getBytes(StandardCharsets.UTF_8));
          record.headers().add("eventType", event.eventType().getBytes(StandardCharsets.UTF_8));
          record.headers().add("schemaVersion", "1".getBytes(StandardCharsets.UTF_8));
          kafka.send(record).get(properties.sendTimeoutMs(), TimeUnit.MILLISECONDS);
          repository.markPublished(event.eventId());
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          repository.markFailed(event.eventId(), "Interrupted while publishing",
              OffsetDateTime.now().plus(properties.retryDelay(event.attempts())));
          return;
        } catch (Exception error) {
          repository.markFailed(event.eventId(), error.getClass().getSimpleName(),
              OffsetDateTime.now().plus(properties.retryDelay(event.attempts())));
          return;
        }
      }
    });
  }
}
