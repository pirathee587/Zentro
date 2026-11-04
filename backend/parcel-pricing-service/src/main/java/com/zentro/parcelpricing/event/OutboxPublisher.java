package com.zentro.parcelpricing.event;

import com.zentro.parcelpricing.repository.OutboxRepository;
import com.zentro.parcelpricing.config.OutboxProperties;
import com.zentro.parcelpricing.repository.OutboxRepository.PendingEvent;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends outbox events to Kafka, at least once and in order per parcel.
 *
 * <p>Each round runs in one database transaction: it locks a batch of due events, sends each one to its mapped
 * topic (key = parcel id, value = the stored envelope unchanged, headers eventId, eventType, schemaVersion) and
 * waits for the broker to confirm. Confirmed events are marked published. A failed event gets its attempt count
 * increased and an exponential back-off; the round then stops so later events (including the same parcel's) are
 * not sent ahead of it. Events that reach the attempt limit stay in the table as "dead" until replayed.
 *
 * <p>Logs carry the event id, type and attempt count only, never the payload.
 */
@Component
@ConditionalOnProperty(prefix = "zentro.outbox", name = "enabled", havingValue = "true")
@EnableScheduling
public class OutboxPublisher {
  public static final String HEADER_EVENT_ID = "eventId";
  public static final String HEADER_EVENT_TYPE = "eventType";
  public static final String HEADER_SCHEMA_VERSION = "schemaVersion";
  public static final String SCHEMA_VERSION = "1";
  static final String UNMAPPED_EVENT_TYPE = "unmapped event type";

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxRepository repository;
  private final KafkaTemplate<String, String> kafka;
  private final OutboxProperties properties;
  private final OutboxMetrics metrics;
  private final TransactionOperations transactions;
  private final Clock clock;

  @Autowired
  public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
      OutboxProperties properties, OutboxMetrics metrics, PlatformTransactionManager transactionManager) {
    this(repository, kafka, properties, metrics, new TransactionTemplate(transactionManager), Clock.systemUTC());
  }

  OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka, OutboxProperties properties,
      OutboxMetrics metrics, TransactionOperations transactions, Clock clock) {
    this.repository = repository;
    this.kafka = kafka;
    this.properties = properties;
    this.metrics = metrics;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${zentro.outbox.poll-interval-ms:1000}")
  public void publishPending() {
    try {
      transactions.executeWithoutResult(status -> publishBatch());
    } catch (RuntimeException ex) {
      // The round is retried on the next poll; nothing is lost because unsent events stay unpublished.
      log.warn("Outbox publishing round failed: {}", ex.getClass().getSimpleName());
    }
  }

  /** Publishes one batch inside the caller's transaction. Returns the number of events published. */
  int publishBatch() {
    List<PendingEvent> batch = repository.fetchBatch(properties.maxAttempts(), properties.batchSize());
    Set<UUID> blockedParcels = new HashSet<>();
    int published = 0;
    for (PendingEvent event : batch) {
      if (blockedParcels.contains(event.aggregateId())) {
        continue;
      }
      String topic = properties.topicFor(event.eventType());
      if (topic == null) {
        recordFailure(event, UNMAPPED_EVENT_TYPE);
        blockedParcels.add(event.aggregateId());
        continue;
      }
      try {
        send(topic, event);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        recordFailure(event, "interrupted while waiting for the broker");
        break;
      } catch (Exception ex) {
        recordFailure(event, describe(ex));
        // The broker is probably unavailable: stop this round instead of waiting on every remaining event.
        break;
      }
      repository.markPublished(event.eventId());
      metrics.published(event.eventType());
      published++;
    }
    return published;
  }

  private void send(String topic, PendingEvent event) throws Exception {
    ProducerRecord<String, String> record =
        new ProducerRecord<>(topic, event.aggregateId().toString(), event.payload());
    record.headers().add(HEADER_EVENT_ID, event.eventId().toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(HEADER_EVENT_TYPE, event.eventType().getBytes(StandardCharsets.UTF_8));
    record.headers().add(HEADER_SCHEMA_VERSION, SCHEMA_VERSION.getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get(properties.sendTimeoutMs(), TimeUnit.MILLISECONDS);
  }

  private void recordFailure(PendingEvent event, String error) {
    OffsetDateTime nextAttemptAt = OffsetDateTime.now(clock).plus(properties.retryDelay(event.attempts()));
    int attempts = repository.markFailed(event.eventId(), error, nextAttemptAt);
    metrics.failed(event.eventType());
    if (attempts >= properties.maxAttempts()) {
      log.error("Outbox event {} ({}) is dead after {} attempts and will not be retried until replayed",
          event.eventId(), event.eventType(), attempts);
    } else {
      log.warn("Outbox event {} ({}) failed attempt {}; next try at {}",
          event.eventId(), event.eventType(), attempts, nextAttemptAt);
    }
  }

  /** Short failure description from the exception type and message; Kafka errors never include the record value. */
  static String describe(Throwable ex) {
    Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
    String message = cause.getMessage();
    return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
