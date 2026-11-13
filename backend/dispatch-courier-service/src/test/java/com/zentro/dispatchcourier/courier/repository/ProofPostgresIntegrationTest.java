package com.zentro.dispatchcourier.courier.repository;

import static org.assertj.core.api.Assertions.*;

import com.zentro.dispatchcourier.courier.dto.SubmitProofRequest;
import com.zentro.dispatchcourier.exception.ProofException;
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

  @Test void diagnosticMetadataSurvivesNewRepositoryAndLegacyRows() {
    org.slf4j.MDC.put("correlationId", "durable-proof-143");
    try { proofs.save(parcel, "courier", evidence); }
    finally { org.slf4j.MDC.remove("correlationId"); }
    var first = proofs.claim(8).orElseThrow();
    assertThat(first.propagation().correlationId()).isEqualTo("durable-proof-143");
    jdbc.update("UPDATE dispatch.proof_of_delivery SET next_attempt_at = now() - interval '1 second'");
    var restarted = new ProofRepository(jdbc);
    var recovered = transactions.execute(status -> restarted.claim(8).orElseThrow());
    assertThat(recovered.propagation()).isEqualTo(first.propagation());
    jdbc.update("UPDATE dispatch.proof_of_delivery SET correlation_id = NULL, traceparent = NULL, tracestate = NULL, next_attempt_at = now() - interval '1 second'");
    var legacy = transactions.execute(status -> restarted.claim(8).orElseThrow());
    assertThat(legacy.propagation().correlationId()).isEqualTo(legacy.proofId().toString());
    assertThat(legacy.propagation().traceparent()).isNull();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void changingOnlyNotesOrReferenceConflictsWithoutChangingStoredProof(boolean changeNotes) {
    var original = proofs.save(parcel, "courier", evidence).proof();
    var changed = new SubmitProofRequest(evidence.recipientName(), changeNotes ? "New note" : null,
        changeNotes ? evidence.proofReference() : "OTHER-REF");
    assertThatThrownBy(() -> proofs.save(parcel, "courier", changed))
        .isInstanceOfSatisfying(ProofException.class, error -> assertThat(error.status()).isEqualTo(409));
    assertThat(proofs.find(parcel).orElseThrow()).isEqualTo(original);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_of_delivery", Integer.class)).isEqualTo(1);
  }
  @Test void concurrentSubmissionsCommitExactlyOneProof() throws Exception {
    var barrier = new CyclicBarrier(2);
    Callable<ProofRepository.Submission> submit = () -> { barrier.await(10, TimeUnit.SECONDS); return proofs.saveOwned(parcel, profile, user, "courier", evidence); };
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
    try { proofs.saveOwned(parcel, profile, user, "courier", request); return true; } catch (ProofException error) { return false; }
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void lockedSubmissionRequiresBothCanonicalOwnerIds(boolean wrongProfile) {
    assertThatThrownBy(() -> proofs.saveOwned(parcel, wrongProfile ? UUID.randomUUID() : profile,
        wrongProfile ? user : UUID.randomUUID(), "foreign", evidence))
        .isInstanceOfSatisfying(ProofException.class, error -> assertThat(error.status()).isEqualTo(403));
    assertThat(proofs.find(parcel)).isEmpty();
  }

  @Test void lockedSubmissionRejectsMissingAssignmentAndRollsBackWithOuterTransaction() {
    assertThatThrownBy(() -> proofs.saveOwned(UUID.randomUUID(), profile, user, "owner", evidence))
        .isInstanceOfSatisfying(ProofException.class, error -> assertThat(error.status()).isEqualTo(404));
    assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
      proofs.saveOwned(parcel, profile, user, "owner", evidence);
      throw new IllegalStateException("rollback");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(proofs.find(parcel)).isEmpty();
  }

  @Test void acceptedProofHoldsOwnerLockUntilCommitThenAllowsExistingReassignmentPolicy() throws Exception {
    var manager = context.getBean(PlatformTransactionManager.class);
    var assignments = new com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository(jdbc, manager);
    var replacement = new com.zentro.dispatchcourier.courier.service.IdentityCourierClient.Courier(
        UUID.randomUUID(), UUID.randomUUID(), "New", true, "ACTIVE");
    try (var pool = Executors.newSingleThreadExecutor()) {
      transactions.executeWithoutResult(status -> {
        proofs.saveOwned(parcel, profile, user, "owner", evidence);
        var blocked = pool.submit(() -> transactions.executeWithoutResult(other -> {
          jdbc.execute("SET LOCAL lock_timeout = '250ms'");
          assignments.reassign(parcel, replacement, "dispatcher");
        }));
        assertThatThrownBy(() -> blocked.get(10, TimeUnit.SECONDS))
            .isInstanceOf(java.util.concurrent.ExecutionException.class)
            .hasRootCauseInstanceOf(java.sql.SQLException.class)
            .satisfies(error -> {
              Throwable root = error;
              while (root.getCause() != null) root = root.getCause();
              assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo("55P03");
            });
        assertThat(assignments.find(parcel).orElseThrow().courierId()).isEqualTo(profile);
      });
    }
    var original = proofs.find(parcel).orElseThrow();
    assignments.reassign(parcel, replacement, "dispatcher");
    assertThat(proofs.find(parcel).orElseThrow()).isEqualTo(original);
    assertThat(assignments.find(parcel).orElseThrow().courierId()).isEqualTo(replacement.id());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "id = gen_random_uuid()", "parcel_id = gen_random_uuid()", "recipient_name = 'Changed'",
      "notes = 'Changed'", "proof_reference = 'Changed'", "submitted_by_subject = 'Changed'",
      "submitted_at = submitted_at + interval '1 second'"})
  void everyEvidenceColumnIsImmutable(String mutation) {
    var original = proofs.save(parcel, "courier", evidence).proof();
    // Only fixed test literals above are accepted as SQL fragments.
    assertThatThrownBy(() -> jdbc.update("UPDATE dispatch.proof_of_delivery SET " + mutation))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(proofs.find(parcel).orElseThrow()).isEqualTo(original);
  }

  @Test void staleCourierAfterReassignmentCannotReadOrSubmitAndDoesNotWriteEvidence() {
    var identity = org.mockito.Mockito.mock(com.zentro.dispatchcourier.courier.service.IdentityCourierClient.class);
    var oldCourier = new com.zentro.dispatchcourier.courier.service.IdentityCourierClient.Courier(
        profile, user, "Old", true, "ACTIVE");
    org.mockito.Mockito.when(identity.currentCourier("old-token")).thenReturn(oldCourier);
    var assignments = new com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository(jdbc,
        context.getBean(PlatformTransactionManager.class));
    assignments.reassign(parcel, new com.zentro.dispatchcourier.courier.service.IdentityCourierClient.Courier(
        UUID.randomUUID(), UUID.randomUUID(), "New", true, "ACTIVE"), "dispatcher");
    var service = new com.zentro.dispatchcourier.courier.service.ProofOfDeliveryService(identity, assignments, proofs);
    var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("old-token")
        .header("alg", "test").subject("old-subject").build();
    var assignmentBefore = jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment");
    var auditBefore = jdbc.queryForList("SELECT * FROM dispatch.assignment_audit");
    var outboxBefore = jdbc.queryForList("SELECT * FROM dispatch.outbox_event");
    for (Runnable rejected : List.<Runnable>of(() -> service.proof(parcel, jwt),
        () -> service.submit(parcel, jwt, evidence))) {
      assertThatThrownBy(rejected::run).isInstanceOfSatisfying(ProofException.class,
          error -> assertThat(error.status()).isEqualTo(403));
    }
    assertThat(proofs.find(parcel)).isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment")).isEqualTo(assignmentBefore);
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.assignment_audit")).isEqualTo(auditBefore);
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.outbox_event")).isEqualTo(outboxBefore);
  }

  @Test void reassignmentBetweenOwnershipReadAndProofInsertRejectsStaleOwner() throws Exception {
    var ownershipRead = new java.util.concurrent.CountDownLatch(1);
    var reassigned = new java.util.concurrent.CountDownLatch(1);
    var manager = context.getBean(PlatformTransactionManager.class);
    var assignments = new com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository(jdbc, manager) {
      @Override public java.util.Optional<Assignment> find(UUID id) {
        var snapshot = super.find(id);
        ownershipRead.countDown();
        try {
          if (!reassigned.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Reassignment timed out");
        } catch (InterruptedException error) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(error);
        }
        return snapshot;
      }
    };
    var identity = org.mockito.Mockito.mock(com.zentro.dispatchcourier.courier.service.IdentityCourierClient.class);
    org.mockito.Mockito.when(identity.currentCourier("old-token")).thenReturn(
        new com.zentro.dispatchcourier.courier.service.IdentityCourierClient.Courier(profile, user, "Old", true, "ACTIVE"));
    var service = new com.zentro.dispatchcourier.courier.service.ProofOfDeliveryService(identity, assignments, proofs);
    var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("old-token")
        .header("alg", "test").subject("old-subject").build();
    var replacement = new com.zentro.dispatchcourier.courier.service.IdentityCourierClient.Courier(
        UUID.randomUUID(), UUID.randomUUID(), "New", true, "ACTIVE");
    var independent = new com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository(jdbc, manager);
    try (var pool = Executors.newSingleThreadExecutor()) {
      var submission = pool.submit(() -> service.submit(parcel, jwt, evidence));
      try {
        assertThat(ownershipRead.await(10, TimeUnit.SECONDS)).isTrue();
        independent.reassign(parcel, replacement, "dispatcher");
      } finally {
        reassigned.countDown();
      }
      var assignmentAfter = jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment");
      var auditAfter = jdbc.queryForList("SELECT * FROM dispatch.assignment_audit");
      var outboxAfter = jdbc.queryForList("SELECT * FROM dispatch.outbox_event");
      assertThatThrownBy(() -> submission.get(10, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .hasCauseInstanceOf(ProofException.class)
          .satisfies(error -> assertThat(((ProofException) error.getCause()).status()).isEqualTo(403));
      assertThat(independent.find(parcel).orElseThrow().courierId()).isEqualTo(replacement.id());
      assertThat(proofs.find(parcel)).isEmpty();
      assertThat(jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment")).isEqualTo(assignmentAfter);
      assertThat(jdbc.queryForList("SELECT * FROM dispatch.assignment_audit")).isEqualTo(auditAfter);
      assertThat(jdbc.queryForList("SELECT * FROM dispatch.outbox_event")).isEqualTo(outboxAfter);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2, 3, 4})
  void invalidEvidenceAtServiceBoundaryLeavesDatabaseUntouched(int invalid) {
    var identity = org.mockito.Mockito.mock(com.zentro.dispatchcourier.courier.service.IdentityCourierClient.class);
    org.mockito.Mockito.when(identity.currentCourier("token")).thenReturn(
        new com.zentro.dispatchcourier.courier.service.IdentityCourierClient.Courier(profile, user, "Owner", true, "ACTIVE"));
    var assignments = new com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository(jdbc,
        context.getBean(PlatformTransactionManager.class));
    var service = new com.zentro.dispatchcourier.courier.service.ProofOfDeliveryService(identity, assignments, proofs);
    var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("token")
        .header("alg", "test").subject("owner").build();
    var request = switch (invalid) {
      case 0 -> new SubmitProofRequest(null, null, "ref");
      case 1 -> new SubmitProofRequest("Owner", null, "\u00a0\ufeff");
      case 2 -> new SubmitProofRequest("Owner", null, "ref\0");
      case 3 -> new SubmitProofRequest("x".repeat(101), null, "ref");
      default -> new SubmitProofRequest("Owner", null, "x".repeat(201));
    };
    var before = jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment");
    var auditBefore = jdbc.queryForList("SELECT * FROM dispatch.assignment_audit");
    var outboxBefore = jdbc.queryForList("SELECT * FROM dispatch.outbox_event");
    assertThatThrownBy(() -> service.submit(parcel, jwt, request))
        .isInstanceOfSatisfying(ProofException.class, error -> assertThat(error.status()).isEqualTo(400));
    assertThat(proofs.find(parcel)).isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment")).isEqualTo(before);
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.assignment_audit")).isEqualTo(auditBefore);
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.outbox_event")).isEqualTo(outboxBefore);
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

