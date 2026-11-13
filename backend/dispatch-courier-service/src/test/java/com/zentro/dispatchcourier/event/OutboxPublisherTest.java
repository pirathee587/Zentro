package com.zentro.dispatchcourier.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class OutboxPublisherTest {
  @Test void failedSendCanBeRecoveredByNewPublisherWithSameEventIdentity() {
    var repository = mock(OutboxRepository.class);
    @SuppressWarnings("unchecked")
    KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    var event = new OutboxRepository.PendingEvent(UUID.randomUUID(), UUID.randomUUID(),
        "delivery.assigned", "{\"synthetic\":true}", 0);
    when(repository.fetchBatch(10, 50)).thenReturn(List.of(event));
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(new IllegalStateException("offline")))
        .thenReturn(CompletableFuture.completedFuture(null));
    var properties = new OutboxProperties(true, 1000, 50, 10, 5000, 1000, 300000,
        Map.of("delivery.assigned", "delivery.assigned"));
    new OutboxPublisher(repository, kafka, properties, manager).publishPending();
    verify(repository).markFailed(eq(event.eventId()), anyString(), any());
    verify(repository, never()).markPublished(any());
    new OutboxPublisher(repository, kafka, properties, manager).publishPending();
    verify(repository).markPublished(event.eventId());
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<ProducerRecord> records = ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka, times(2)).send(records.capture());
    for (var record : records.getAllValues()) {
      assertThat(record.key()).isEqualTo(event.aggregateId().toString());
      assertThat(record.value()).isEqualTo(event.payload());
      assertThat(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
          .isEqualTo(event.eventId().toString());
    }
    // At-least-once delivery: this tests stable replay identity, not consumer deduplication.
    verify(manager, times(2)).commit(any());
  }
}
