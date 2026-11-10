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
    when(identity.requireEligible(any(), anyString())).thenReturn(courier);
    var firstService = new DispatchService(source, repository, identity);
    var secondRepository = new AssignmentRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()));
    var secondService = new DispatchService(source, secondRepository, identity);
    try (var threads = Executors.newFixedThreadPool(2)) {
      Callable<String> first = () -> attempt(firstService, "dispatcher-one");
      Callable<String> second = () -> attempt(secondService, "dispatcher-two");
      var a = threads.submit(first); var b = threads.submit(second);
      assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder("created", "DELIVERY_ALREADY_ASSIGNED");
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.delivery_assignment", Integer.class)).isEqualTo(1);
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

  private String attempt(DispatchService service, String dispatcher) {
    try { service.assign(parcelId, courier.id(), dispatcher, "token"); return "created"; }
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
      } finally {
        // Always release before executor.close(), including assertion failures.
        if (commit) holder.commit(); else holder.rollback();
      }
      assertThat(pending.get(5, TimeUnit.SECONDS).parcelId()).isEqualTo(parcelId);
      assertThat(jdbc.queryForObject("""
          SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND database =
          (SELECT oid FROM pg_database WHERE datname = current_database())
          """, Integer.class)).isZero();
    }
  }
}
