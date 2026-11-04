package com.zentro.dispatchcourier;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker = true)
class ProofPostgresIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  static AnnotationConfigApplicationContext context;
  static JdbcTemplate jdbc;
  static ProofRepository proofs;
  static TransactionTemplate transactions;
  UUID parcel = UUID.randomUUID(), profile = UUID.randomUUID(), user = UUID.randomUUID();
  SubmitProofRequest evidence = new SubmitProofRequest("Nimal", null, "REF-1");
  @Configuration @EnableTransactionManagement static class Config {
    @Bean javax.sql.DataSource dataSource() { return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()); }
    @Bean JdbcTemplate jdbc(javax.sql.DataSource source) { return new JdbcTemplate(source); }
    @Bean PlatformTransactionManager manager(javax.sql.DataSource source) { return new DataSourceTransactionManager(source); }
    @Bean ProofRepository proofs(JdbcTemplate jdbc) { return new ProofRepository(jdbc); }
  }
  @BeforeAll static void setup() {
    Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .schemas("dispatch").defaultSchema("dispatch").load().migrate();
    context = new AnnotationConfigApplicationContext(Config.class);
    jdbc = context.getBean(JdbcTemplate.class); proofs = context.getBean(ProofRepository.class);
    transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
  }
  @AfterAll static void close() { if (context != null) context.close(); }
  @BeforeEach void assignment() {
    jdbc.execute("TRUNCATE dispatch.proof_of_delivery, dispatch.delivery_assignment");
    jdbc.update("INSERT INTO dispatch.delivery_assignment(parcel_id,courier_profile_id,courier_user_id,assigned_by_subject) VALUES (?,?,?,?)",
        parcel, profile, user, "dispatcher");
  }
  @Test void persistsServerTimestampAndIdenticalRetryWithoutOverwrite() {
    Instant before = Instant.now().minusSeconds(1);
    var first = proofs.save(parcel, "courier-subject", evidence);
    var replay = proofs.save(parcel, "courier-subject", evidence);
    assertThat(first.created()).isTrue(); assertThat(replay.created()).isFalse();
    assertThat(replay.proof()).isEqualTo(first.proof());
    assertThat(first.proof().submittedAt()).isAfter(before).isBefore(Instant.now().plusSeconds(1));
    assertThat(first.proof().completionState()).isEqualTo("PENDING");
    assertThatThrownBy(() -> proofs.save(parcel, "courier-subject", new SubmitProofRequest("Other", null, "REF-1")))
        .isInstanceOf(ProofException.class);
    assertThat(proofs.find(parcel).orElseThrow()).isEqualTo(first.proof());
  }
  @Test void concurrentSubmissionsCommitExactlyOneProof() throws Exception {
    var barrier = new CyclicBarrier(2);
    Callable<ProofRepository.Submission> submit = () -> { barrier.await(10, TimeUnit.SECONDS); return proofs.save(parcel, "courier", evidence); };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = pool.invokeAll(List.of(submit, submit));
      var one = futures.get(0).get(); var two = futures.get(1).get();
      assertThat(one.proof().id()).isEqualTo(two.proof().id());
      assertThat(one.created()).isNotEqualTo(two.created());
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_of_delivery", Integer.class)).isEqualTo(1);
  }
  @Test void concurrentDifferentEvidenceHasOneWinnerAndOneConflict() throws Exception {
    var barrier = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = pool.invokeAll(List.<Callable<Boolean>>of(
          () -> attempt(barrier, evidence), () -> attempt(barrier, new SubmitProofRequest("Other", null, "REF-2"))));
      assertThat(List.of(futures.get(0).get(), futures.get(1).get())).containsExactlyInAnyOrder(true, false);
    }
  }
  boolean attempt(CyclicBarrier barrier, SubmitProofRequest request) throws Exception {
    barrier.await(10, TimeUnit.SECONDS);
    try { proofs.save(parcel, "courier", request); return true; } catch (ProofException error) { return false; }
  }
  @Test void databaseRejectsMissingAssignmentOversizeBlankAndEvidenceMutation() {
    assertThatThrownBy(() -> proofs.save(UUID.randomUUID(), "courier", evidence)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> proofs.save(parcel, "courier", new SubmitProofRequest("x".repeat(101), null, "ref"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> proofs.save(parcel, "courier", new SubmitProofRequest(" ", null, "ref"))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    proofs.save(parcel, "courier", evidence);
    assertThatThrownBy(() -> jdbc.update("UPDATE dispatch.proof_of_delivery SET recipient_name = 'Other'"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(() -> jdbc.update("DELETE FROM dispatch.proof_of_delivery"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }
  @Test void outerTransactionRollbackLeavesNoProof() {
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
      proofs.save(parcel, "courier", evidence); throw new IllegalStateException("rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(proofs.find(parcel)).isEmpty();
  }
  @Test void claimLeaseRestartRecoveryAndStaleAcknowledgmentAreSafe() {
    proofs.save(parcel, "courier", evidence);
    var abandoned = proofs.claim(8).orElseThrow();
    assertThat(proofs.claim(8)).isEmpty();
    jdbc.update("UPDATE dispatch.proof_of_delivery SET next_attempt_at = now() - interval '1 second'");
    // A new repository instance simulates restart; work is recovered from PostgreSQL.
    var restarted = new ProofRepository(jdbc);
    var recovered = restarted.claim(8).orElseThrow();
    assertThat(recovered.attempts()).isEqualTo(2);
    proofs.finish(abandoned, "COMPLETED", null, 0);
    assertThat(proofs.find(parcel).orElseThrow().completionState()).isEqualTo("PENDING");
    restarted.finish(recovered, "COMPLETED", null, 0);
    assertThat(proofs.find(parcel).orElseThrow().completedAt()).isNotNull();
    assertThat(proofs.claim(8)).isEmpty();
  }
  @Test void exhaustedCrashedLeaseBlocksAndListsOmitOtherCouriers() {
    proofs.save(parcel, "courier", evidence);
    proofs.claim(1).orElseThrow();
    jdbc.update("UPDATE dispatch.proof_of_delivery SET next_attempt_at = now() - interval '1 second'");
    assertThat(proofs.claim(1)).isEmpty();
    assertThat(proofs.find(parcel).orElseThrow().completionState()).isEqualTo("BLOCKED");
    assertThat(proofs.deliveries(profile, user)).hasSize(1);
    assertThat(proofs.deliveries(UUID.randomUUID(), user)).isEmpty();
  }
  @Test void retrySchedulePersistsAcrossWorkerRounds() {
    proofs.save(parcel, "courier", evidence);
    var first = proofs.claim(8).orElseThrow();
    proofs.finish(first, "PENDING", "PARCEL_SERVICE_UNAVAILABLE", 30);
    assertThat(proofs.claim(8)).isEmpty();
    assertThat(jdbc.queryForObject("SELECT attempts FROM dispatch.proof_of_delivery", Integer.class)).isEqualTo(1);
  }
  @Test void concurrentWorkersClaimTheProofOnlyOnce() throws Exception {
    proofs.save(parcel, "courier", evidence);
    var barrier = new CyclicBarrier(2);
    Callable<Boolean> claim = () -> { barrier.await(10, TimeUnit.SECONDS); return proofs.claim(8).isPresent(); };
    try (var pool = Executors.newFixedThreadPool(2)) {
      var futures = pool.invokeAll(List.of(claim, claim));
      assertThat(List.of(futures.get(0).get(), futures.get(1).get())).containsExactlyInAnyOrder(true, false);
    }
  }
}
