package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import com.zentro.notificationintegration.delivery.service.NotificationDispatcher;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfigureObservability
@TestPropertySource(properties = {"zentro.notifications.mock.fail-mode=first-attempt", "zentro.notifications.retry.poll-interval-ms=600000"})
class DurableNotificationPropagationIntegrationTest extends AbstractNotificationIntegrationTest {
  @Autowired NotificationDispatcher dispatcher;
  @Autowired PlatformTransactionManager manager;
  @Test void kafkaContextSurvivesRepositoryRecreationAndRetryAndLegacyRowsWork() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID parcelId = UUID.randomUUID();
    String trace = "0123456789abcdef0123456789abcdef";
    var record = new ProducerRecord<String, String>(STATUS_TOPIC, parcelId.toString(), statusEvent(eventId, parcelId, newTrackingRef(), "PICKED_UP", null));
    record.headers().add("X-Correlation-Id", "durable-notification-143".getBytes(StandardCharsets.UTF_8));
    record.headers().add("traceparent", ("00-" + trace + "-0123456789abcdef-01").getBytes(StandardCharsets.UTF_8));
    kafka.send(record).get();
    awaitRow(eventId, "RETRY");
    UUID id = jdbc.queryForObject("SELECT id FROM notification.notification_attempt WHERE event_id = ?", UUID.class, eventId);
    var restarted = new NotificationRepository(jdbc);
    var row = new TransactionTemplate(manager).execute(status -> restarted.lockForDelivery(id).orElseThrow());
    assertThat(row.propagation().correlationId()).isEqualTo("durable-notification-143");
    assertThat(row.propagation().traceparent()).startsWith("00-" + trace + "-");
    dispatcher.deliver(id);
    assertThat(awaitRow(eventId, "SENT").get("attempts")).isEqualTo(2);
    jdbc.update("UPDATE notification.notification_attempt SET status = 'RETRY', correlation_id = NULL, traceparent = NULL, tracestate = NULL WHERE id = ?", id);
    var legacy = new TransactionTemplate(manager).execute(status -> restarted.lockForDelivery(id).orElseThrow());
    assertThat(legacy.propagation().correlationId()).isEqualTo(eventId.toString());
    dispatcher.deliver(id);
    assertThat(awaitRow(eventId, "SENT").get("attempts")).isEqualTo(3);
  }
}
