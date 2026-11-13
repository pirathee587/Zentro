package com.zentro.notificationintegration.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.zentro.notificationintegration.delivery.provider.NotificationSender;
import com.zentro.notificationintegration.delivery.service.NotificationDispatcher;
import com.zentro.notificationintegration.messaging.event.ParcelEventParser;
import com.zentro.notificationintegration.notification.repository.NotificationRepository;
import com.zentro.notificationintegration.notification.service.NotificationService;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

/** Real disposable PostgreSQL/Kafka; sender is the mock channel, JWT decoding is mocked by the base. */
@TestPropertySource(properties = {
    "spring.datasource.hikari.connection-timeout=1000",
    "spring.datasource.hikari.validation-timeout=500"
})
class NotificationRecoveryIntegrationTest extends AbstractNotificationIntegrationTest {
  @Autowired DefaultErrorHandler errorHandler;
  @Autowired KafkaListenerEndpointRegistry listeners;
  @Autowired NotificationService service;
  @Autowired ParcelEventParser parser;
  @Autowired NotificationDispatcher dispatcher;
  @Autowired PlatformTransactionManager transactions;
  @Autowired com.zentro.notificationintegration.config.NotificationProperties properties;
  @Autowired com.zentro.notificationintegration.notification.service.NotificationMetrics metrics;
  @Autowired java.time.Clock clock;
  @SpyBean NotificationRepository repository;
  @SpyBean NotificationSender sender;
  // Drive durable work explicitly so the scheduler cannot race injected rollback windows.
  @org.springframework.boot.test.mock.mockito.MockBean
  com.zentro.notificationintegration.delivery.service.NotificationRetryScheduler retryScheduler;

  @Test
  void databaseRestartDoesNotCommitFailedRecordAndRecoversOneOutcome() throws Exception {
    UUID warmup = UUID.randomUUID();
    UUID parcel = UUID.randomUUID();
    send(CREATED_TOPIC, parcel, createdEvent(warmup, parcel, newTrackingRef()));
    awaitRow(warmup, "SENT");
    CountDownLatch failed = new CountDownLatch(1);
    errorHandler.setRetryListeners((record, exception, attempt) -> failed.countDown());
    var docker = DockerClientFactory.instance().client();
    try (var admin = AdminClient.create(java.util.Map.of(
        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
      docker.stopContainerCmd(POSTGRES.getContainerId()).withTimeout(1).exec();
      UUID event = UUID.randomUUID();
      var metadata = kafka.send(CREATED_TOPIC, parcel.toString(),
          createdEvent(event, parcel, newTrackingRef())).get(30, TimeUnit.SECONDS).getRecordMetadata();
      assertThat(failed.await(30, TimeUnit.SECONDS)).as("listener encountered database outage").isTrue();
      var position = admin.listConsumerGroupOffsets(NotificationService.CONSUMER)
          .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
          .get(new TopicPartition(metadata.topic(), metadata.partition()));
      assertThat(position == null || position.offset() <= metadata.offset())
          .as("failed event offset must not be committed").isTrue();
      docker.startContainerCmd(POSTGRES.getContainerId()).exec();
      await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() ->
          Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)));
      awaitRow(event, "SENT");
      assertThat(rowsFor(event)).hasSize(1);
      await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(
          admin.listConsumerGroupOffsets(NotificationService.CONSUMER).partitionsToOffsetAndMetadata()
              .get(10, TimeUnit.SECONDS).get(new TopicPartition(metadata.topic(), metadata.partition())).offset())
          .isGreaterThan(metadata.offset()));
    } finally {
      if (!Boolean.TRUE.equals(docker.inspectContainerCmd(POSTGRES.getContainerId()).exec().getState().getRunning())) {
        docker.startContainerCmd(POSTGRES.getContainerId()).exec();
      }
      await().atMost(Duration.ofSeconds(30)).ignoreExceptions().until(() ->
          Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)));
      errorHandler.setRetryListeners();
    }
  }

  @Test
  void committedEventSurvivesBrokerAndListenerRestartAndReplay() throws Exception {
    listeners.stop();
    var docker = DockerClientFactory.instance().client();
    UUID event = UUID.randomUUID();
    UUID parcel = UUID.randomUUID();
    String envelope = createdEvent(event, parcel, newTrackingRef());
    try {
      send(CREATED_TOPIC, parcel, envelope); // broker acknowledges before it is restarted
      assertThat(rowsFor(event)).isEmpty();
      docker.restartContainerCmd(KAFKA.getContainerId()).withTimeout(5).exec();
    } finally {
      listeners.start();
    }
    awaitRow(event, "SENT");
    send(CREATED_TOPIC, parcel, envelope);
    UUID marker = UUID.randomUUID();
    send(CREATED_TOPIC, parcel, createdEvent(marker, parcel, newTrackingRef()));
    awaitRow(marker, "SENT"); // same key/partition drains the replay
    assertThat(rowsFor(event)).hasSize(1);
    assertThat(rowsFor(event).get(0).get("attempts")).isEqualTo(1);
  }

  @Test
  void acceptanceThenRollbackRepeatsSendButCommitsOneOutcome() throws Exception {
    UUID event = UUID.randomUUID();
    UUID delivery = service.handle(parser.parse(CREATED_TOPIC,
        createdEvent(event, UUID.randomUUID(), newTrackingRef()))).orElseThrow();
    var failure = new DataAccessResourceFailureException("synthetic result write failure after acceptance");
    org.mockito.Mockito.doThrow(failure).doCallRealMethod().when(repository).markSent(delivery, 1);

    assertThatThrownBy(() -> dispatcher.deliver(delivery)).isSameAs(failure);
    assertThat(rowsFor(event).get(0)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM notification.delivery_attempt_history WHERE event_id = ?", Long.class, event)).isZero();

    dispatcher.deliver(delivery);
    assertThat(awaitRow(event, "SENT").get("attempts")).isEqualTo(1);
    org.mockito.Mockito.verify(sender, org.mockito.Mockito.times(2)).send(
        org.mockito.ArgumentMatchers.argThat(message -> message.eventId().equals(event)));
    assertThat(rowsFor(event)).hasSize(1);
  }

  @Test
  void offsetAcknowledgementFailureLeavesDurableWorkAndReplayDoesNotCreateAnotherOutcome() throws Exception {
    UUID event = UUID.randomUUID();
    String envelope = createdEvent(event, UUID.randomUUID(), newTrackingRef());
    var record = new org.apache.kafka.clients.consumer.ConsumerRecord<String, String>(
        CREATED_TOPIC, 0, 0, "synthetic-offset-failure", envelope);
    var consumer = new com.zentro.notificationintegration.messaging.consumer.ParcelEventConsumer(
        parser, service, dispatcher);
    var failure = new org.apache.kafka.common.KafkaException("synthetic acknowledgement failure");
    assertThatThrownBy(() -> consumer.onParcelEvent(record, () -> { throw failure; })).isSameAs(failure);
    assertThat(rowsFor(event).get(0)).containsEntry("status", "PENDING").containsEntry("attempts", 0);
    org.mockito.Mockito.verify(sender, org.mockito.Mockito.never()).send(
        org.mockito.ArgumentMatchers.argThat(message -> message.eventId().equals(event)));

    consumer.onParcelEvent(record, () -> {});
    assertThat(rowsFor(event)).hasSize(1);
    UUID delivery = jdbc.queryForObject(
        "SELECT id FROM notification.notification_attempt WHERE event_id = ?", UUID.class, event);
    dispatcher.deliver(delivery);
    assertThat(awaitRow(event, "SENT").get("attempts")).isEqualTo(1);
  }

  @Test
  void distinctReorderedStatusesAreBothDeliveredRatherThanSilentlySuppressed() throws Exception {
    UUID parcel = UUID.randomUUID();
    UUID delivered = UUID.randomUUID();
    UUID pickedUp = UUID.randomUUID();
    String reference = newTrackingRef();
    send(STATUS_TOPIC, parcel, statusEvent(delivered, parcel, reference, "DELIVERED", null));
    send(STATUS_TOPIC, parcel, statusEvent(pickedUp, parcel, reference, "PICKED_UP", null));
    assertThat(awaitRow(delivered, "SENT")).containsEntry("parcel_status", "DELIVERED");
    assertThat(awaitRow(pickedUp, "SENT")).containsEntry("parcel_status", "PICKED_UP");
    assertThat(rowsFor(delivered)).hasSize(1);
    assertThat(rowsFor(pickedUp)).hasSize(1);
  }

  @Test
  void killedWorkerProcessRestartsAndDrainsDurablePendingWork(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory)
      throws Exception {
    listeners.stop();
    Process worker = null;
    try {
      UUID first = UUID.randomUUID();
      service.handle(parser.parse(CREATED_TOPIC, createdEvent(first, UUID.randomUUID(), newTrackingRef())));
      worker = startWorker(directory, "first.log");
      awaitRow(first, "SENT");
      long firstPid = worker.pid();
      worker.destroyForcibly();
      assertThat(worker.waitFor(10, TimeUnit.SECONDS)).isTrue();

      UUID second = UUID.randomUUID();
      service.handle(parser.parse(CREATED_TOPIC, createdEvent(second, UUID.randomUUID(), newTrackingRef())));
      assertThat(rowsFor(second).get(0)).containsEntry("status", "PENDING");
      worker = startWorker(directory, "restarted.log");
      assertThat(worker.pid()).isNotEqualTo(firstPid);
      assertThat(awaitRow(second, "SENT").get("attempts")).isEqualTo(1);
      assertThat(rowsFor(first)).hasSize(1);
      assertThat(rowsFor(first).get(0).get("attempts")).isEqualTo(1);
    } finally {
      if (worker != null && worker.isAlive()) {
        worker.destroyForcibly();
        assertThat(worker.waitFor(10, TimeUnit.SECONDS)).isTrue();
      }
      listeners.start();
    }
  }

  private Process startWorker(java.nio.file.Path directory, String logName) throws java.io.IOException {
    String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var builder = new ProcessBuilder(
        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx256m", "-cp", classpath,
        "com.zentro.notificationintegration.NotificationIntegrationApplication", "--server.port=0")
        .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve(logName).toFile());
    var environment = builder.environment();
    environment.put("DB_URL", POSTGRES.getJdbcUrl());
    environment.put("DB_USER", POSTGRES.getUsername());
    environment.put("DB_PASSWORD", POSTGRES.getPassword());
    environment.put("KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers());
    environment.put("NOTIFICATIONS_CONSUMER_ENABLED", "true");
    environment.put("NOTIFICATION_RETRY_POLL_MS", "200");
    environment.put("NOTIFICATION_MOCK_FAIL_MODE", "none");
    environment.put("WEBHOOKS_ENABLED", "false");
    // No authenticated HTTP calls are made. Explicit JWKS avoids issuer discovery at startup;
    // the child still uses the production JWT decoder/security configuration, without a mock.
    environment.put("JWT_ISSUER_URI", "http://127.0.0.1:1/test-realm");
    environment.put("JWT_JWKS_URI", "http://127.0.0.1:1/test-jwks");
    return builder.start();
  }

  @Test
  void rollbackBeforeCommitNeverSendsAndPendingWorkSurvivesDispatcherRecreation() throws Exception {
    UUID event = UUID.randomUUID();
    var parsed = parser.parse(CREATED_TOPIC, createdEvent(event, UUID.randomUUID(), newTrackingRef()));
    assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
      service.handle(parsed);
      throw new IllegalStateException("synthetic worker failure before commit");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(rowsFor(event)).isEmpty();
    org.mockito.Mockito.verify(sender, org.mockito.Mockito.never()).send(
        org.mockito.ArgumentMatchers.argThat(message -> message.eventId().equals(event)));
    UUID delivery = service.handle(parsed).orElseThrow();
    // Fresh worker object uses only durable state; this is not a whole-process restart.
    var recreated = new NotificationDispatcher(repository, sender, metrics, properties, clock);
    new TransactionTemplate(transactions).executeWithoutResult(tx -> recreated.deliver(delivery));
    assertThat(awaitRow(event, "SENT").get("attempts")).isEqualTo(1);
  }
}
