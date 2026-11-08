package com.zentro.dispatchcourier.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.courier.service.*;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
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
  UUID a, b;
  IdentityCourierClient.Courier courierA, courierB;
  static final String ROOT = "/api/v1/courier/deliveries";
  static final Map<String, Object> EVIDENCE = Map.of("recipientName", "Recipient A", "proofReference", "REF-A");

  @BeforeEach void setup() {
    // HttpURLConnection's streaming client throws on a 401 POST before exposing
    // the response. Use the existing JDK client to assert actual denial responses.
    http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
    jdbc.execute("TRUNCATE dispatch.proof_of_delivery, dispatch.delivery_assignment");
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
}
