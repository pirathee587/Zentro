package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.ParcelPricingApplication;
import com.zentro.parcelpricing.integration.support.OutboxFaultTriggers;
import com.zentro.parcelpricing.outbox.publisher.OutboxPublisher;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.service.ParcelStatusService;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * ZD-062 restart recovery: events committed while the publisher was not running survive an application restart and
 * are published afterwards; a row held by another transaction is skipped and published once that transaction ends;
 * an interrupted publish (Kafka confirmed, but "mark published" did not commit) is sent again with the same eventId.
 *
 * <p>Each scenario starts the real S2 application twice against the same PostgreSQL and Kafka containers: first with
 * the publisher off (the "before restart" process), then with it on. All connection settings are passed as
 * command-line arguments, which take precedence over any local .env file, so only the containers are used.
 * Publish-after-commit under broker outage and ordering are covered by OutboxKafkaIntegrationTest and #196.
 * Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxRestartRecoveryIntegrationTest {
  private static final String BOOKING = """
      {
        "sender": {"name": "Restart Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Restart Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
        "parcel": {"weightKg": 1.0, "category": "Documents", "size": "SMALL"},
        "destinationType": "OTHER_DISTRICT"
      }
      """;
  private static final Duration WAIT = Duration.ofSeconds(60);

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

  private ConfigurableApplicationContext app;
  private KafkaConsumer<String, String> consumer;
  private final Map<String, AtomicInteger> received = new ConcurrentHashMap<>();
  private JdbcTemplate jdbc;

  /** Starts S2 against the containers; {@code publisher} switches the outbox publisher on or off. */
  private ConfigurableApplicationContext start(boolean publisher) {
    return new SpringApplicationBuilder(ParcelPricingApplication.class)
        .web(WebApplicationType.SERVLET)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
            "--zentro.security.audience=zentro-api",
            "--zentro.security.jwks-uri=http://localhost:8180/realms/zentro/protocol/openid-connect/certs",
            "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
            "--spring.kafka.producer.properties[max.block.ms]=2000",
            "--spring.kafka.producer.properties[request.timeout.ms]=2000",
            "--spring.kafka.producer.properties[delivery.timeout.ms]=4000",
            "--zentro.outbox.enabled=" + publisher,
            "--zentro.outbox.poll-interval-ms=200",
            "--zentro.outbox.send-timeout-ms=2000",
            "--zentro.outbox.retry-base-delay-ms=200",
            "--zentro.outbox.retry-max-delay-ms=1000",
            "--zentro.outbox.max-attempts=100",
            "--zentro.outbox.topic-partitions=3");
  }

  @BeforeEach
  void setUp() {
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "zd062-" + UUID.randomUUID());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
    props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, "500");
    consumer = new KafkaConsumer<>(props);
    consumer.subscribe(java.util.regex.Pattern.compile("parcel\\..*"));
  }

  @AfterEach
  void tearDown() {
    if (app != null) {
      app.close();
    }
    consumer.close();
  }

  /** Drains the topics and counts every received copy per eventId header. */
  private void poll() {
    for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
      Header header = record.headers().lastHeader(OutboxPublisher.HEADER_EVENT_ID);
      String eventId = header == null ? "missing" : new String(header.value(), StandardCharsets.UTF_8);
      received.computeIfAbsent(eventId, id -> new AtomicInteger()).incrementAndGet();
    }
  }

  private int copies(UUID eventId) {
    AtomicInteger count = received.get(eventId.toString());
    return count == null ? 0 : count.get();
  }

  /** Books a parcel and moves it to PICKED_UP with the publisher off; returns the parcel id. */
  private UUID bookAndPickUp(ConfigurableApplicationContext before) throws Exception {
    CreateParcelBookingRequest request = before.getBean(ObjectMapper.class)
        .readValue(BOOKING, CreateParcelBookingRequest.class);
    UUID parcelId = before.getBean(ParcelBookingService.class)
        .book("restart-customer", request, null).booking().id();
    before.getBean(ParcelStatusService.class)
        .updateStatus(parcelId, ParcelStatus.PICKED_UP, null, null, "restart-dispatcher", "DISPATCHER");
    return parcelId;
  }

  /** Polls until no new copy of {@code eventId} has arrived for 2 seconds; returns the final count. */
  private int waitUntilQuiet(UUID eventId) {
    int last = copies(eventId);
    long quietSince = System.currentTimeMillis();
    long deadline = quietSince + WAIT.toMillis();
    while (System.currentTimeMillis() - quietSince < 2000 && System.currentTimeMillis() < deadline) {
      poll();
      int now = copies(eventId);
      if (now != last) {
        last = now;
        quietSince = System.currentTimeMillis();
      }
    }
    return last;
  }

  private List<UUID> pendingEvents(UUID parcelId) {
    return jdbc.queryForList("""
        SELECT event_id FROM parcel.outbox_event WHERE aggregate_id = ? AND published_at IS NULL ORDER BY occurred_at
        """, UUID.class, parcelId);
  }

  private boolean published(UUID eventId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT published_at IS NOT NULL FROM parcel.outbox_event WHERE event_id = ?", Boolean.class, eventId));
  }

  @Test
  @DisplayName("Events committed before a restart are still there and are each published once afterwards")
  void committedEventsSurviveRestart() throws Exception {
    ConfigurableApplicationContext before = start(false);
    List<UUID> parcels = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      parcels.add(bookAndPickUp(before));
    }
    before.close();

    Set<UUID> pending = new HashSet<>();
    for (UUID parcelId : parcels) {
      List<UUID> events = pendingEvents(parcelId);
      assertThat(events).as("created + status event survive the stop").hasSize(2);
      pending.addAll(events);
    }

    app = start(true);
    await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
      poll();
      return pending.stream().allMatch(id -> published(id) && copies(id) >= 1);
    });
    for (int i = 0; i < 5; i++) {
      poll(); // keep reading to catch any late duplicate
    }

    assertThat(pending).allSatisfy(id -> assertThat(copies(id)).as("copies of %s", id).isEqualTo(1));
    assertThat(jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.outbox_event WHERE aggregate_id = ANY (?) AND attempts > 0
        """, Integer.class, (Object) parcels.toArray(UUID[]::new))).isZero();
  }

  @Test
  @DisplayName("A row held by another transaction is skipped, then published once that transaction rolls back")
  void lockedRowIsPublishedAfterTheLockIsReleased() throws Exception {
    ConfigurableApplicationContext before = start(false);
    UUID parcelId = bookAndPickUp(before);
    before.close();
    List<UUID> events = pendingEvents(parcelId);
    UUID created = events.get(0);

    try (Connection lock = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
        POSTGRES.getPassword())) {
      lock.setAutoCommit(false);
      try (PreparedStatement select = lock.prepareStatement(
          "SELECT event_id FROM parcel.outbox_event WHERE event_id = ? FOR UPDATE")) {
        select.setObject(1, created);
        try (ResultSet rows = select.executeQuery()) {
          assertThat(rows.next()).isTrue();
        }
      }

      app = start(true);
      long until = System.currentTimeMillis() + 3000; // about 15 publisher rounds while the row is locked
      while (System.currentTimeMillis() < until) {
        poll();
      }
      assertThat(published(created)).as("locked row is not published").isFalse();
      assertThat(copies(created)).isZero();
      assertThat(published(events.get(1))).as("later event of the same parcel waits behind it").isFalse();

      lock.rollback(); // the other transaction aborts and releases the lock
    }

    await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
      poll();
      return events.stream().allMatch(id -> published(id) && copies(id) >= 1);
    });
    assertThat(copies(created)).isEqualTo(1);
    assertThat(copies(events.get(1))).isEqualTo(1);
  }

  @Test
  @DisplayName("Kafka confirmed the send but 'mark published' failed: the same eventId is sent again, never lost")
  void interruptedPublishIsResentWithTheSameEventId() throws Exception {
    ConfigurableApplicationContext before = start(false);
    UUID parcelId = bookAndPickUp(before);
    before.close();
    UUID created = pendingEvents(parcelId).get(0);

    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectPublishedMark(jdbc)) {
      app = start(true);
      await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
        poll();
        return copies(created) >= 2;
      });
      assertThat(published(created)).as("the mark keeps failing, so the row stays pending").isFalse();
    }

    await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> {
      poll();
      return published(created);
    });
    // Copies sent before the mark may still be on their way; wait until nothing new arrives for 2 s.
    int settled = waitUntilQuiet(created);
    long until = System.currentTimeMillis() + 3000; // about 15 more publisher rounds
    while (System.currentTimeMillis() < until) {
      poll();
    }

    assertThat(settled).as("at-least-once: resent while unmarked").isGreaterThanOrEqualTo(2);
    assertThat(copies(created)).as("no further copies once marked").isEqualTo(settled);
    assertThat(received.keySet()).doesNotContain("missing");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parcel.outbox_event WHERE event_id = ?", Integer.class, created))
        .as("still one row: the eventId never changes").isEqualTo(1);
  }
}
