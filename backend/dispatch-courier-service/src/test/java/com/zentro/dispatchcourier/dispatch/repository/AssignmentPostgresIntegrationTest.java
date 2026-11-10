package com.zentro.dispatchcourier.dispatch.repository;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.dispatch.service.DispatchService;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
import com.zentro.dispatchcourier.exception.DispatchException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL/Flyway/transactions, test-only parcel and Identity doubles. */
@Testcontainers
class AssignmentPostgresIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  static JdbcTemplate jdbc;
  static AssignmentRepository repository;
  final UUID parcelId = UUID.randomUUID();
  final IdentityCourierClient.Courier courier = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "Test", true, "ACTIVE");

  @BeforeAll static void database() {
    var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    Flyway.configure().dataSource(dataSource).schemas("dispatch").defaultSchema("dispatch").load().migrate();
    jdbc = new JdbcTemplate(dataSource);
    repository = new AssignmentRepository(jdbc, new DataSourceTransactionManager(dataSource));
  }

  @BeforeEach void reset() {
    jdbc.execute("TRUNCATE dispatch.proof_of_delivery, dispatch.assignment_audit, dispatch.outbox_event, dispatch.delivery_assignment");
  }

  @Test void competingServicesCommitExactlyOneAssignmentAndExcludeItFromQueue() throws Exception {
    var barrier = new CyclicBarrier(2);
    var parcel = new ParcelSource.Parcel(parcelId, "TEST-ONLY", "Pickup", "Dropoff", Instant.now());
    ParcelSource source = new ParcelSource() {
      @Override public Snapshot eligibleParcels(String token) { return new Snapshot(List.of(parcel), true); }
      @Override public <T> T withEligibleParcel(UUID id, String token, Function<Parcel, T> action) {
        try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception error) { throw new RuntimeException(error); }
        return action.apply(parcel);
      }
    };
    var identity = mock(IdentityCourierClient.class);
    var otherCourier = syntheticCourier(2, true);
    when(identity.requireEligible(eq(courier.id()), anyString())).thenReturn(courier);
    when(identity.requireEligible(eq(otherCourier.id()), anyString())).thenReturn(otherCourier);
    var firstService = new DispatchService(source, repository, identity);
    var secondRepository = new AssignmentRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    var secondService = new DispatchService(source, secondRepository, identity);
    try (var threads = Executors.newFixedThreadPool(2)) {
      Callable<String> first = () -> attempt(firstService, "dispatcher-one", courier.id());
      Callable<String> second = () -> attempt(secondService, "dispatcher-two", otherCourier.id());
      var a = threads.submit(first); var b = threads.submit(second);
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("created", "DELIVERY_ALREADY_ASSIGNED");
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.delivery_assignment", Integer.class)).isEqualTo(1);
    var winner = repository.find(parcelId).orElseThrow();
    assertThat(winner.courierId()).isEqualTo(winner.assignedBy().equals("dispatcher-one") ? courier.id() : otherCourier.id());
    assertThat(repository.assignmentCounts()).containsExactlyEntriesOf(java.util.Map.of(winner.courierId(), 1L));
    assertIndependentCounts();
    assertThat(jdbc.queryForObject("SELECT assigned_by_subject FROM dispatch.delivery_assignment", String.class))
        .isIn("dispatcher-one", "dispatcher-two");
    var queue = firstService.queue("token", "", null, null, "newest");
    assertThat(queue.items()).isEmpty(); assertThat(queue.total()).isZero();
  }

  @Test void databaseRejectsDuplicateEvenOutsideServiceAndKeepsOriginalAudit() {
    var saved = repository.insert(parcelId, courier, "winner");
    assertThatThrownBy(() -> jdbc.update("INSERT INTO dispatch.delivery_assignment (parcel_id,courier_profile_id,courier_user_id,assigned_by_subject) VALUES (?,?,?,?)",
        parcelId, UUID.randomUUID(), UUID.randomUUID(), "loser")).isInstanceOf(DataIntegrityViolationException.class);
    assertThat(saved.assignedAt()).isNotNull();
    assertThat(jdbc.queryForObject("SELECT assigned_by_subject FROM dispatch.delivery_assignment", String.class)).isEqualTo("winner");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.assignment_audit", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.outbox_event", Integer.class)).isEqualTo(1);
  }

  @Test void reassignmentAndCancellationWriteAuditedEventsAtomically() {
    var replacement = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "Replacement", true, "ACTIVE");
    repository.insert(parcelId, courier, "dispatcher");
    var reassigned = repository.reassign(parcelId, replacement, "dispatcher");
    assertThat(reassigned.courierId()).isEqualTo(replacement.id());
    repository.cancel(parcelId, "dispatcher");
    assertThat(repository.exists(parcelId)).isFalse();
    assertThat(jdbc.queryForList("SELECT action, old_courier_profile_id, new_courier_profile_id FROM dispatch.assignment_audit"))
        .extracting(row -> row.get("action"))
        .containsExactly("ASSIGNED", "REASSIGNED", "CANCELLED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.outbox_event", Integer.class)).isEqualTo(3);
  }

  @Test void cancellationOfMissingAssignmentFailsWithoutAuditOrEvent() {
    assertThatThrownBy(() -> repository.cancel(parcelId, "dispatcher"))
        .isInstanceOfSatisfying(DispatchException.class,
            error -> assertThat(error.code()).isEqualTo("DELIVERY_NOT_ASSIGNED"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.assignment_audit", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.outbox_event", Integer.class)).isZero();
  }

  @Test void failedTransactionRollsBackAndNextRequestCanAssign() {
    assertThatThrownBy(() -> repository.insert(parcelId, courier, "x".repeat(256)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(repository.exists(parcelId)).isFalse();
    assertThat(repository.insert(parcelId, courier, "retry").assignedBy()).isEqualTo("retry");
  }

  @Test void lifetimeWorkloadMatchesIndependentRecountAcrossProofStatesAndInactiveCouriers() {
    var second = syntheticCourier(2, true);
    var inactive = syntheticCourier(3, false);
    var zero = syntheticCourier(4, true);
    var directory = List.of(courier, second, inactive, zero);
    UUID pending = fixtureAssignment(101, courier);
    fixtureProof(pending, "PENDING");
    UUID completed = fixtureAssignment(102, courier);
    fixtureProof(completed, "COMPLETED");
    fixtureAssignment(103, courier); // No proof is different from a pending proof.
    fixtureProof(fixtureAssignment(104, second), "REJECTED");
    fixtureProof(fixtureAssignment(105, second), "BLOCKED");
    fixtureProof(fixtureAssignment(106, inactive), "COMPLETED");

    var identity = mock(IdentityCourierClient.class);
    when(identity.listAll("token")).thenReturn(directory);
    var source = mock(ParcelSource.class);
    var result = new DispatchService(source, repository, identity).workload("token", "", "");
    var expected = java.util.Map.of(courier.id(), 3L, second.id(), 2L, inactive.id(), 1L, zero.id(), 0L);
    assertThat(result.total()).isEqualTo(4); // Courier rows, not the six assigned parcels.
    assertThat(result.overloadConfigured()).isFalse();
    for (var item : result.items()) {
      assertThat(item.assignmentCount()).isEqualTo(expected.get(item.courierId()));
      assertThat(item.availability()).isEqualTo(item.courierId().equals(inactive.id())
          ? DispatchService.Availability.UNAVAILABLE : DispatchService.Availability.AVAILABLE);
    }
    assertIndependentCounts();
    assertThat(result.items().stream().mapToLong(DispatchService.CourierWorkload::assignmentCount).sum()).isEqualTo(6);
    verify(identity, times(1)).listAll("token");
    verifyNoInteractions(source);
  }

  @Test void emptyAssignmentDatabaseIncludesDirectoryCouriersWithZeroCounts() {
    assertThat(repository.assignmentCounts()).isEmpty();
    var identity = mock(IdentityCourierClient.class);
    when(identity.listAll("token")).thenReturn(List.of(courier));
    var service = new DispatchService(mock(ParcelSource.class), repository, identity);
    assertThat(service.workload("token", "", "").items()).singleElement()
        .satisfies(item -> assertThat(item.assignmentCount()).isZero());
    when(identity.listAll("token")).thenReturn(List.of());
    assertThat(service.workload("token", "", "").total()).isZero();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"NONE", "PENDING", "COMPLETED", "REJECTED", "BLOCKED"})
  void reassignmentIsRejectedWithoutChangingAttributionOrProof(String state) {
    UUID parcel = fixtureAssignment(110, courier);
    if (!state.equals("NONE")) fixtureProof(parcel, state);
    var before = jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment");
    var proofBefore = jdbc.queryForList("SELECT * FROM dispatch.proof_of_delivery");
    var identity = mock(IdentityCourierClient.class);
    var source = mock(ParcelSource.class);
    var service = new DispatchService(source, repository, identity);
    assertThatThrownBy(() -> service.assign(parcel, syntheticCourier(2, true).id(), "synthetic-dispatcher", "token"))
        .isInstanceOfSatisfying(DispatchException.class,
            error -> assertThat(error.code()).isEqualTo("DELIVERY_ALREADY_ASSIGNED"));
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment")).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.proof_of_delivery")).isEqualTo(proofBefore);
    assertIndependentCounts();
    verifyNoInteractions(identity, source);
  }

  @Test void timezoneAndMidnightDoNotCreateAnUndocumentedWorkloadWindow() throws Exception {
    var original = java.util.TimeZone.getDefault();
    org.springframework.jdbc.datasource.SingleConnectionDataSource utcDataSource = null;
    try {
      java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Colombo"));
      utcDataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(jdbc.getDataSource().getConnection(), true);
      var utcJdbc = new JdbcTemplate(utcDataSource);
      utcJdbc.execute("SET TIME ZONE 'UTC'");
      assertThat(utcJdbc.queryForObject("SHOW timezone", String.class)).isEqualTo("UTC");
      assertThat(java.util.TimeZone.getDefault().getID()).isEqualTo("Asia/Colombo");
      var utcRepository = new AssignmentRepository(utcJdbc, new DataSourceTransactionManager(utcDataSource));
      var instants = List.of("2026-11-07T18:29:59.999999Z", "2026-11-07T18:30:00Z",
          "2026-11-08T00:00:00Z", "2026-11-08T18:30:00Z");
      for (int index = 0; index < instants.size(); index++) {
        UUID parcel = fixtureAssignment(120 + index, courier);
        jdbc.update("UPDATE dispatch.delivery_assignment SET assigned_at = ? WHERE parcel_id = ?",
            java.time.OffsetDateTime.parse(instants.get(index)), parcel);
        if (index == 0) {
          fixtureProof(parcel, "COMPLETED");
          jdbc.update("UPDATE dispatch.proof_of_delivery SET completed_at = ? WHERE parcel_id = ?",
              java.time.OffsetDateTime.parse("2026-11-09T00:00:00Z"), parcel);
        }
      }
      assertThat(repository.assignmentCounts()).containsExactlyEntriesOf(java.util.Map.of(courier.id(), 4L));
      assertThat(utcRepository.assignmentCounts()).isEqualTo(repository.assignmentCounts());
      assertThat(new com.zentro.dispatchcourier.courier.repository.ProofRepository(jdbc)
          .deliveries(courier.id(), courier.userId())).hasSize(4);
      assertIndependentCounts();
    } finally {
      if (utcDataSource != null) utcDataSource.destroy();
      java.util.TimeZone.setDefault(original);
    }
  }

  @Test void databaseRejectsInvalidProofStatusNullAssignmentTimeAndOrphanProof() {
    UUID parcel = fixtureAssignment(130, courier);
    fixtureProof(parcel, "PENDING");
    assertThatThrownBy(() -> jdbc.update("UPDATE dispatch.proof_of_delivery SET completion_state = 'FAILED' WHERE parcel_id = ?", parcel))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("UPDATE dispatch.delivery_assignment SET assigned_at = NULL WHERE parcel_id = ?", parcel))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> fixtureProof(new UUID(0, 999), "PENDING"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(repository.assignmentCounts()).containsExactlyEntriesOf(java.util.Map.of(courier.id(), 1L));
  }

  @Test void largerFixtureCountsEveryDistinctParcelWithoutProofJoinMultiplication() throws Exception {
    jdbc.update("""
        INSERT INTO dispatch.delivery_assignment(parcel_id, courier_profile_id, courier_user_id, assigned_by_subject)
        SELECT md5('synthetic-parcel-' || value)::uuid, ?, ?, 'synthetic-dispatcher'
        FROM generate_series(1, 10000) value
        """, courier.id(), courier.userId());
    assertThat(repository.assignmentCounts()).containsExactlyEntriesOf(java.util.Map.of(courier.id(), 10000L));
    assertIndependentCounts();
    jdbc.execute("ANALYZE dispatch.delivery_assignment");
    var plan = jdbc.queryForList("""
        EXPLAIN (ANALYZE, BUFFERS)
        SELECT courier_profile_id, COUNT(*) AS assignment_count
        FROM dispatch.delivery_assignment GROUP BY courier_profile_id
        """, String.class);
    java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
    java.nio.file.Files.write(java.nio.file.Path.of("target/workload-query-explain.txt"), plan);
  }

  private static IdentityCourierClient.Courier syntheticCourier(int index, boolean active) {
    return new IdentityCourierClient.Courier(new UUID(0, index), new UUID(1, index),
        "Synthetic Courier " + index, active, "ACTIVE");
  }

  private UUID fixtureAssignment(int index, IdentityCourierClient.Courier assignee) {
    UUID parcel = new UUID(2, index);
    repository.insert(parcel, assignee, "synthetic-dispatcher");
    return parcel;
  }

  private void fixtureProof(UUID parcel, String state) {
    jdbc.update("""
        INSERT INTO dispatch.proof_of_delivery(id, parcel_id, recipient_name, proof_reference,
            submitted_by_subject, completion_state, completed_at)
        VALUES (?, ?, 'Synthetic Recipient', 'SYNTHETIC-PROOF', 'synthetic-courier', ?,
            CASE WHEN ? = 'COMPLETED' THEN TIMESTAMPTZ '2026-11-09 00:00:00+00' ELSE NULL END)
        """, UUID.randomUUID(), parcel, state, state);
  }

  /** Recount raw rows in Java: deliberately independent of production SQL COUNT/GROUP BY. */
  private void assertIndependentCounts() {
    var counts = new java.util.HashMap<UUID, Long>();
    var parcels = new java.util.HashSet<UUID>();
    jdbc.query("SELECT parcel_id, courier_profile_id FROM dispatch.delivery_assignment", row -> {
      assertThat(parcels.add(row.getObject("parcel_id", UUID.class))).isTrue();
      counts.merge(row.getObject("courier_profile_id", UUID.class), 1L, Long::sum);
    });
    assertThat(repository.assignmentCounts()).containsExactlyInAnyOrderEntriesOf(counts);
    assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(parcels.size());
  }

  private String attempt(DispatchService service, String dispatcher, UUID targetCourier) {
    try { service.assign(parcelId, targetCourier, dispatcher, "token"); return "created"; }
    catch (DispatchException error) { return error.code(); }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void parcelAdvisoryLockBlocksInsertUntilCommitOrRollback(boolean commit) throws Exception {
    try (var holder = jdbc.getDataSource().getConnection();
         var threads = Executors.newSingleThreadExecutor()) {
      holder.setAutoCommit(false);
      try (var lock = holder.prepareStatement(
          "SELECT pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))")) {
        lock.setString(1, parcelId.toString());
        lock.execute();
      }
      var pending = threads.submit(() -> repository.insert(parcelId, courier, "waiting-dispatcher"));
      try {
        // Observe PostgreSQL's actual waiter, rather than guessing from a sleep/future timeout.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean waiting = false;
        while (System.nanoTime() < deadline) {
          waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
              SELECT EXISTS(SELECT 1 FROM pg_locks
                WHERE locktype = 'advisory' AND NOT granted AND database =
                  (SELECT oid FROM pg_database WHERE datname = current_database()))
              """, Boolean.class));
          if (waiting) break;
          Thread.sleep(20);
        }
        assertThat(waiting).as("repository must wait on the advisory lock before INSERT").isTrue();
        assertThat(pending.isDone()).isFalse();
        assertThat(repository.exists(parcelId)).isFalse();
        assertThat(repository.assignmentCounts()).isEmpty(); // Uncommitted contender is invisible.
      } finally {
        // Always release before executor.close(), including assertion failures.
        if (commit) holder.commit(); else holder.rollback();
      }
      assertThat(pending.get(5, TimeUnit.SECONDS).parcelId()).isEqualTo(parcelId);
      assertThat(repository.assignmentCounts()).containsExactlyEntriesOf(java.util.Map.of(courier.id(), 1L));
      assertThat(jdbc.queryForObject("""
          SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND database =
          (SELECT oid FROM pg_database WHERE datname = current_database())
          """, Integer.class)).isZero();
    }
  }
}
