package com.zentro.dispatchcourier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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

  @BeforeEach void reset() { jdbc.execute("TRUNCATE dispatch.delivery_assignment"); }

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
}
