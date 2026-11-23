package com.zentro.dispatchcourier.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.courier.service.*;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
import com.zentro.dispatchcourier.pod.SupabaseProofStorage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real S3 HTTP/filter chain/services/Flyway/PostgreSQL. S1, parcel source and JWT decoder are doubles.
 * Not a Keycloak, gateway or cross-service E2E test. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "zentro.pod.worker-enabled=false", "zentro.security.issuer-uri=http://test/realm",
    "zentro.security.audience=zentro-api"})
class CourierWorkflowIntegrationTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }
  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired ProofRepository proofs;
  @MockBean JwtDecoder decoder;
  @MockBean IdentityCourierClient identity;
  @MockBean ParcelSource source;
  @MockBean ParcelStatusClient parcels;
  @MockBean SupabaseProofStorage storage;
  UUID a, b;
  IdentityCourierClient.Courier courierA, courierB;
  static final String ROOT = "/api/v1/courier/deliveries";
  static final Map<String, Object> EVIDENCE = Map.of("recipientName", "Recipient A", "proofReference", "REF-A");

  @BeforeEach void setup() {
    // HttpURLConnection's streaming client throws on a 401 POST before exposing
    // the response. Use the existing JDK client to assert actual denial responses.
    http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    jdbc.execute("TRUNCATE dispatch.proof_completion_attempt, dispatch.proof_review_audit, dispatch.proof_review, dispatch.proof_revision, dispatch.proof_upload_stage, dispatch.proof_of_delivery, dispatch.delivery_assignment");
    a = UUID.randomUUID(); b = UUID.randomUUID();
    courierA = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "A", true, "ACTIVE");
    courierB = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "B", true, "ACTIVE");
    for (String token : List.of("courier-a", "courier-b", "dispatcher", "customer")) {
      String role = token.startsWith("courier") ? "COURIER" : token.toUpperCase();
      when(decoder.decode(token)).thenReturn(Jwt.withTokenValue(token).header("alg", "test")
          .subject("subject-" + token).claim("realm_access", Map.of("roles", List.of(role)))
          .claim("permissions", token.equals("dispatcher") ? List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN") : List.of())
          .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build());
    }
    when(identity.currentCourier("courier-a")).thenReturn(courierA);
    when(identity.currentCourier("courier-b")).thenReturn(courierB);
    when(identity.requireEligible(eq(courierA.id()), anyString())).thenReturn(courierA);
    when(identity.requireEligible(eq(courierB.id()), anyString())).thenReturn(courierB);
    when(source.withEligibleParcel(any(), anyString(), any())).thenAnswer(call -> {
      UUID id = call.getArgument(0);
      Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      return action.apply(new ParcelSource.Parcel(id, "TEST", "Pickup", "Dropoff", Instant.now()));
    });
    assign(a, courierA); assign(b, courierB);
  }
  void assign(UUID id, IdentityCourierClient.Courier courier) {
    assertThat(request(HttpMethod.POST, "/api/v1/dispatch/assignments", "dispatcher",
        Map.of("parcelId", id, "courierId", courier.id())).getStatusCode().value()).isEqualTo(201);
  }
  ResponseEntity<String> request(HttpMethod method, String path, String token, Object body) {
    var headers = new HttpHeaders();
    if (token != null) headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }
  ResponseEntity<String> submit(UUID id, String token, Object evidence) {
    return request(HttpMethod.POST, ROOT + "/" + id + "/proof", token, evidence);
  }

  ResponseEntity<String> submitPhoto(UUID id, String token, UUID key, String recipient) {
    var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
    body.add("recipientName", recipient);
    body.add("proofReference", "PHOTO-" + key);
    body.add("notes", "isolated fixture");
    body.add("photo", new ByteArrayResource(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 1}) {
      @Override public String getFilename() { return "delivery.jpg"; }
    });
    var headers = new HttpHeaders();
    if (token != null) headers.setBearerAuth(token);
    headers.setContentType(MediaType.MULTIPART_FORM_DATA);
    headers.set("Idempotency-Key", key.toString());
    return http.exchange(ROOT + "/" + id + "/proof-photo", HttpMethod.POST,
        new HttpEntity<>(body, headers), String.class);
  }

  UUID review(UUID parcel, String decision, Instant updatedAt) {
    return review(parcel, decision, updatedAt, UUID.randomUUID());
  }
  UUID review(UUID parcel, String decision, Instant updatedAt, UUID proof) {
    UUID revision = UUID.randomUUID(), review = UUID.randomUUID();
    jdbc.update("INSERT INTO dispatch.proof_of_delivery(id,parcel_id,recipient_name,proof_reference,submitted_by_subject,completion_state) VALUES(?,?,?,?,?,'PENDING')",
        proof, parcel, "Fixture recipient", "FIXTURE", "subject-courier-a");
    jdbc.update("INSERT INTO dispatch.proof_revision(id,proof_id,revision_no,object_path,original_filename,content_type,byte_size,content_sha256,submitted_by_subject) VALUES(?,?,1,?,?,?,?,?,?)",
        revision, proof, "proofs/" + revision + ".jpg", "test.jpg", "image/jpeg", 10L, "a".repeat(64), "subject-courier-a");
    if ("COMPLETION_PROCESSING".equals(decision) || "PENDING_REVIEW".equals(decision)) {
      jdbc.update("INSERT INTO dispatch.proof_review(id,revision_id,decision,updated_at) VALUES(?,?,?,?)",
          review, revision, decision, java.sql.Timestamp.from(updatedAt));
    } else {
      jdbc.update("INSERT INTO dispatch.proof_review(id,revision_id,decision,reviewed_by_subject,reviewed_at,updated_at) VALUES(?,?,?,?,?,?)",
          review, revision, decision, "subject-dispatcher", java.sql.Timestamp.from(updatedAt), java.sql.Timestamp.from(updatedAt));
    }
    return review;
  }

  @Test void workloadHttpContractUsesPersistedLifetimeCountsIncludingCompletedProofs() throws Exception {
    UUID extra = new UUID(10, 1);
    assign(extra, courierA);
    var zero = new IdentityCourierClient.Courier(new UUID(10, 2), new UUID(11, 2), null, true, "ACTIVE");
    var inactive = new IdentityCourierClient.Courier(new UUID(10, 3), new UUID(11, 3), "Synthetic Inactive", false, "ACTIVE");
    when(identity.listAll("dispatcher")).thenReturn(List.of(courierB, zero, inactive, courierA));
    assertThat(submit(a, "courier-a", EVIDENCE).getStatusCode().value()).isEqualTo(201);
    new ProofCompletionWorker(proofs, parcels, 8).completePending();
    assertThat(proofs.find(a).orElseThrow().completionState()).isEqualTo("COMPLETED");

    var response = request(HttpMethod.GET, "/api/v1/dispatch/workload", "dispatcher", null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var body = json.readTree(response.getBody());
    assertThat(body.path("total").asInt()).isEqualTo(4);
    assertThat(body.path("overloadConfigured").asBoolean()).isFalse();
    var expected = Map.of(courierA.id(), 2L, courierB.id(), 1L, zero.id(), 0L, inactive.id(), 0L);
    long sum = 0;
    for (var item : body.path("items")) {
      UUID courierId = UUID.fromString(item.path("courierId").asText());
      long count = item.path("assignmentCount").asLong();
      assertThat(count).isEqualTo(expected.get(courierId));
      sum += count;
    }
    assertThat(sum).isEqualTo(jdbc.queryForObject("SELECT count(DISTINCT parcel_id) FROM dispatch.delivery_assignment", Long.class));
    assertThat(body.path("items").get(3).path("name").isNull()).isTrue(); // Null names sort last.
    assertThat(response.getBody()).doesNotContain("recipientName", "proofReference", "phone", "address", "assignedBy");

    var spec = json.readTree(java.nio.file.Path.of("../../docs/api/openapi/dispatch.openapi.json").toFile());
    assertDocumentedObject(body, spec.path("components").path("schemas").path("Workload"));
    for (var item : body.path("items"))
      assertDocumentedObject(item, spec.path("components").path("schemas").path("CourierWorkload"));
  }

  private static void assertDocumentedObject(com.fasterxml.jackson.databind.JsonNode value,
      com.fasterxml.jackson.databind.JsonNode schema) {
    var actualNames = new java.util.HashSet<String>();
    value.fieldNames().forEachRemaining(actualNames::add);
    var documentedNames = new java.util.HashSet<String>();
    schema.path("properties").fieldNames().forEachRemaining(documentedNames::add);
    assertThat(actualNames).containsExactlyInAnyOrderElementsOf(documentedNames);
    value.fields().forEachRemaining(field -> {
      var rule = schema.path("properties").path(field.getKey());
      if (field.getValue().isNull()) {
        assertThat(rule.path("anyOf").findValuesAsText("type")).contains("null");
      } else {
        String type = rule.path("type").asText("string");
        assertThat(switch (type) {
          case "integer" -> field.getValue().isIntegralNumber();
          case "boolean" -> field.getValue().isBoolean();
          case "array" -> field.getValue().isArray();
          default -> field.getValue().isTextual();
        }).as("documented type for %s", field.getKey()).isTrue();
        if (rule.has("enum")) {
          var allowed = new java.util.ArrayList<String>();
          rule.path("enum").forEach(option -> allowed.add(option.asText()));
          assertThat(allowed).contains(field.getValue().asText());
        }
      }
    });
  }

  @Test void assignedVisibilityAndCrossCourierDenialUsePersistedAssignments() {
    assertThat(request(HttpMethod.GET, ROOT, "courier-a", null).getBody()).contains(a.toString()).doesNotContain(b.toString());
    assertThat(request(HttpMethod.GET, ROOT, "courier-b", null).getBody()).contains(b.toString()).doesNotContain(a.toString());
    for (String suffix : List.of("", "/proof"))
      assertThat(request(HttpMethod.GET, ROOT + "/" + a + suffix, "courier-b", null).getStatusCode().value()).isEqualTo(403);
    assertThat(submit(a, "courier-b", EVIDENCE).getStatusCode().value()).isEqualTo(403);
    assertThat(proofs.find(a)).isEmpty();
  }

  @Test void deniedDispatcherPermissionLeavesDatabaseAndUpstreamsUntouched() {
    var original = jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment ORDER BY parcel_id");
    clearInvocations(identity, source, parcels);
    for (Object permissions : List.of(List.of(), List.of("DISPATCH_VIEW"), "DISPATCH_ASSIGN", List.of("dispatch_assign"))) {
      when(decoder.decode("restricted-dispatcher")).thenReturn(Jwt.withTokenValue("restricted-dispatcher")
          .header("alg", "test").subject("restricted-dispatcher")
          .claim("realm_access", Map.of("roles", List.of("DISPATCHER")))
          .claim("permissions", permissions).build());
      assertThat(request(HttpMethod.POST, "/api/v1/dispatch/assignments", "restricted-dispatcher",
          Map.of("parcelId", UUID.randomUUID(), "courierId", courierA.id())).getStatusCode().value()).isEqualTo(403);
    }
    assertThat(jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment ORDER BY parcel_id")).isEqualTo(original);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_of_delivery", Integer.class)).isZero();
    verifyNoInteractions(identity, source, parcels);
  }
  @Test void proofReplayConflictAndParcelAssociationSurviveHttpRoundTrips() {
    assertThat(submit(a, "courier-a", EVIDENCE).getStatusCode().value()).isEqualTo(201);
    var original = proofs.find(a).orElseThrow();
    assertThat(original.parcelId()).isEqualTo(a);
    assertThat(original.submittedBySubject()).isEqualTo("subject-courier-a");
    assertThat(submit(a, "courier-a", EVIDENCE).getStatusCode().value()).isEqualTo(200);
    assertThat(submit(a, "courier-a", Map.of("recipientName", "Different", "proofReference", "OTHER"))
        .getStatusCode().value()).isEqualTo(409);
    assertThat(proofs.find(a).orElseThrow()).isEqualTo(original);
    assertThat(proofs.find(b)).isEmpty();
  }
  @Test void simultaneousHttpReplaysStoreOneProof() throws Exception {
    var start = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> send = () -> { start.await(5, TimeUnit.SECONDS); return submit(a, "courier-a", EVIDENCE).getStatusCode().value(); };
      var first = pool.submit(send); var second = pool.submit(send);
      assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 200);
    }
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_of_delivery", Integer.class)).isEqualTo(1);
  }
  @Test void rejectedHttpOperationsLeaveBothParcelsWithoutProof() {
    assertThat(submit(a, null, EVIDENCE).getStatusCode().value()).isEqualTo(401);
    assertThat(submit(a, "customer", EVIDENCE).getStatusCode().value()).isEqualTo(403);
    assertThat(submit(a, "courier-a", Map.of("recipientName", " ")).getStatusCode().value()).isEqualTo(400);
    assertThat(proofs.find(a)).isEmpty(); assertThat(proofs.find(b)).isEmpty();
  }

  @Test void freshIneligibilityAndAssignmentConflictsDoNotChangeStoredOwnership() {
    UUID unassigned = UUID.randomUUID();
    when(identity.requireEligible(eq(courierA.id()), anyString())).thenThrow(
        new org.springframework.web.server.ResponseStatusException(HttpStatus.CONFLICT, "Courier is inactive"));
    assertThat(request(HttpMethod.POST, "/api/v1/dispatch/assignments", "dispatcher",
        Map.of("parcelId", unassigned, "courierId", courierA.id())).getStatusCode().value()).isEqualTo(409);
    assertThat(request(HttpMethod.POST, "/api/v1/dispatch/assignments", "dispatcher",
        Map.of("parcelId", a, "courierId", courierB.id())).getStatusCode().value()).isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.delivery_assignment", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT courier_profile_id FROM dispatch.delivery_assignment WHERE parcel_id = ?",
        UUID.class, a)).isEqualTo(courierA.id());
  }

  @Test void persistedWorkerCompletesOnlyTheSubmittedParcel() {
    submit(a, "courier-a", EVIDENCE);
    new ProofCompletionWorker(proofs, parcels, 8).completePending();
    var saved = proofs.find(a).orElseThrow();
    assertThat(saved.completionState()).isEqualTo("COMPLETED");
    assertThat(saved.completedAt()).isNotNull();
    assertThat(proofs.find(b)).isEmpty();
    verify(parcels).complete(a); verify(parcels, never()).complete(b);
  }
  @Test void persistedRejectedCompletionRetainsEvidenceAndCannotResubmitDifferentProof() {
    submit(a, "courier-a", EVIDENCE);
    var before = proofs.find(a).orElseThrow();
    doThrow(new com.zentro.dispatchcourier.exception.CompletionFailure("REJECTED", "DELIVERY_TRANSITION_REJECTED"))
        .when(parcels).complete(a);
    new ProofCompletionWorker(proofs, parcels, 8).completePending();
    var after = proofs.find(a).orElseThrow();
    assertThat(after.completionState()).isEqualTo("REJECTED");
    assertThat(after.completedAt()).isNull();
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.recipientName()).isEqualTo(before.recipientName());
    assertThat(submit(a, "courier-a", Map.of("recipientName", "Replacement", "proofReference", "OTHER"))
        .getStatusCode().value()).isEqualTo(409);
  }
  @Test void restartedWorkerRetriesPersistedFailureAndAcknowledgesSameProof() {
    submit(a, "courier-a", EVIDENCE);
    var before = proofs.find(a).orElseThrow();
    doThrow(new com.zentro.dispatchcourier.exception.CompletionFailure("PENDING", "PARCEL_SERVICE_UNAVAILABLE"))
        .doNothing().when(parcels).complete(a);
    new ProofCompletionWorker(proofs, parcels, 8).completePending();
    assertThat(proofs.find(a).orElseThrow().completionState()).isEqualTo("PENDING");
    // Advance durable scheduling, avoiding wall-clock sleeps; evidence remains immutable.
    jdbc.update("UPDATE dispatch.proof_of_delivery SET next_attempt_at = now() - interval '1 second' WHERE parcel_id = ?", a);
    new ProofCompletionWorker(proofs, parcels, 8).completePending();
    var after = proofs.find(a).orElseThrow();
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.submittedAt()).isEqualTo(before.submittedAt());
    assertThat(after.completionState()).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT attempts FROM dispatch.proof_of_delivery WHERE parcel_id = ?", Integer.class, a)).isEqualTo(2);
    verify(parcels, times(2)).complete(a);
  }

  @Test void courierMultipartUploadIsOwnedIdempotentAndCreatesOneRevision() {
    UUID key = UUID.randomUUID();
    assertThat(submitPhoto(a, "courier-a", key, "Photo recipient").getStatusCode().value()).isEqualTo(201);
    assertThat(submitPhoto(a, "courier-a", key, "Photo recipient").getStatusCode().value()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_of_delivery WHERE parcel_id=?", Integer.class, a)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_revision", Integer.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_upload_stage WHERE idempotency_key=?", Integer.class, key)).isEqualTo(1);
    verify(storage, times(1)).uploadToPath(contains("proofs/staged/" + a), any(), eq("image/jpeg"));
    UUID otherKey = UUID.randomUUID();
    assertThat(submitPhoto(a, "courier-b", otherKey, "Not owner").getStatusCode().value()).isEqualTo(403);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_revision", Integer.class)).isEqualTo(1);
  }

  @Test void storageFailureLeavesNoProofOrRevisionForRetry() {
    UUID key = UUID.randomUUID();
    doThrow(new IllegalStateException("isolated storage unavailable")).when(storage)
        .uploadToPath(anyString(), any(), anyString());
    assertThat(submitPhoto(a, "courier-a", key, "Photo recipient").getStatusCode().is5xxServerError()).isTrue();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_of_delivery", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_revision", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT state FROM dispatch.proof_upload_stage WHERE idempotency_key=?", String.class, key)).isEqualTo("UPLOADING");
  }

  @Test void simultaneousCompletionClaimsInvokeS2ExactlyOnce() throws Exception {
    UUID review = review(a, "COMPLETION_PENDING", Instant.now());
    doAnswer(invocation -> { Thread.sleep(250); return null; }).when(parcels).complete(a);
    var gate = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Integer> call = () -> { gate.await(5, TimeUnit.SECONDS); return request(HttpMethod.POST,
          "/api/v1/dispatch/proof-reviews/" + review + "/complete", "dispatcher", null).getStatusCode().value(); };
      var one = pool.submit(call); var two = pool.submit(call);
      assertThat(List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS))).contains(200, 409);
    }
    verify(parcels, times(1)).complete(a);
    assertThat(jdbc.queryForObject("SELECT decision FROM dispatch.proof_review WHERE id=?", String.class, review)).isEqualTo("COMPLETED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_completion_attempt WHERE review_id=? AND outcome='CONFIRMED'", Integer.class, review)).isEqualTo(1);
  }

  @Test void staleRecoveryReconcilesDeliveredAndBlocksUncertainWithoutReplay() {
    UUID delivered = review(a, "COMPLETION_PROCESSING", Instant.now().minusSeconds(360));
    when(parcels.getParcel(a)).thenReturn(com.zentro.contracts.parcel.v1.ParcelResponse.newBuilder()
        .setParcelId(a.toString()).setStatus("DELIVERED").build());
    assertThat(request(HttpMethod.POST, "/api/v1/dispatch/proof-reviews/" + delivered + "/recover", "dispatcher", null).getStatusCode().value()).isEqualTo(200);
    assertThat(jdbc.queryForObject("SELECT decision FROM dispatch.proof_review WHERE id=?", String.class, delivered)).isEqualTo("COMPLETED");
    verify(parcels, never()).complete(a);

    UUID uncertain = review(b, "COMPLETION_PROCESSING", Instant.now().minusSeconds(360));
    when(parcels.getParcel(b)).thenThrow(new IllegalStateException("S2 unavailable"));
    assertThat(request(HttpMethod.POST, "/api/v1/dispatch/proof-reviews/" + uncertain + "/recover", "dispatcher", null).getStatusCode().value()).isEqualTo(503);
    assertThat(jdbc.queryForObject("SELECT decision FROM dispatch.proof_review WHERE id=?", String.class, uncertain)).isEqualTo("BLOCKED");
    assertThat(jdbc.queryForObject("SELECT outcome FROM dispatch.proof_completion_attempt WHERE review_id=?", String.class, uncertain)).isEqualTo("BLOCKED_FAILURE");
    verify(parcels, never()).complete(b);
  }

  @Test void podEndpointsEnforceRolesAndOptimisticReviewVersion() {
    UUID pending = review(a, "PENDING_REVIEW", Instant.now());
    String base = "/api/v1/dispatch/proof-reviews/" + pending;
    assertThat(request(HttpMethod.POST, base + "/approve", null, Map.of("version", 0)).getStatusCode().value()).isEqualTo(401);
    assertThat(request(HttpMethod.POST, base + "/approve", "courier-a", Map.of("version", 0)).getStatusCode().value()).isEqualTo(403);
    assertThat(request(HttpMethod.POST, base + "/reject", "dispatcher", Map.of("version", 0, "reason", " ")).getStatusCode().value()).isEqualTo(400);
    assertThat(request(HttpMethod.POST, base + "/approve", "dispatcher", Map.of("version", 0)).getStatusCode().value()).isEqualTo(200);
    assertThat(request(HttpMethod.POST, base + "/approve", "dispatcher", Map.of("version", 0)).getStatusCode().value()).isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.proof_review_audit WHERE review_id=?", Integer.class, pending)).isEqualTo(1);
    assertThat(request(HttpMethod.POST, base + "/complete", "courier-a", null).getStatusCode().value()).isEqualTo(403);
  }

  @Test void recoveryExcludesHistoricalBlockedProof() {
    UUID historical = UUID.fromString("a4f5e154-0bea-4df3-b04b-7df25aecc0a1");
    UUID review = review(a, "COMPLETION_PROCESSING", Instant.now().minusSeconds(360), historical);
    assertThat(request(HttpMethod.POST, "/api/v1/dispatch/proof-reviews/" + review + "/recover", "dispatcher", null).getStatusCode().value()).isEqualTo(409);
    assertThat(jdbc.queryForObject("SELECT decision FROM dispatch.proof_review WHERE id=?", String.class, review)).isEqualTo("COMPLETION_PROCESSING");
    verify(parcels, never()).getParcel(a);
  }
}
