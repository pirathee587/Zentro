package com.zentro.dispatchcourier.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
import com.zentro.dispatchcourier.exception.DispatchException;
import com.zentro.dispatchcourier.support.StubHttpServer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** S3 HTTP, security, transactions and PostgreSQL; local S1 and parcel-port responses only.
 * The parcel fixture path is test-only, not an S2 contract or a production adapter. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "zentro.pod.worker-enabled=false", "zentro.outbox.enabled=false",
    "zentro.security.issuer-uri=http://test/realm", "zentro.security.audience=zentro-api"})
class AssignmentToCourierScenarioTest {
  @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
  static final StubHttpServer identity = StubHttpServer.start();
  static final StubHttpServer parcelPort = StubHttpServer.start();
  static final String DISPATCH = "/api/v1/dispatch";
  static final String DELIVERIES = "/api/v1/courier/deliveries";
  static final String ROLE_ONLY = "dispatcher-role-only";
  static final UUID PARCEL = UUID.fromString("11111111-1111-4111-8111-000000000001");
  static final UUID UNASSIGNED = UUID.fromString("11111111-1111-4111-8111-000000000002");
  static final IdentityCourierClient.Courier FIRST = new IdentityCourierClient.Courier(
      new UUID(2, 1), new UUID(3, 1), "Courier One", true, "ACTIVE");
  static final IdentityCourierClient.Courier SECOND = new IdentityCourierClient.Courier(
      new UUID(2, 2), new UUID(3, 2), "Courier Two", true, "ACTIVE");

  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("zentro.identity.base-url", identity::baseUrl);
  }

  @Autowired TestRestTemplate http;
  @Autowired JdbcTemplate jdbc;
  @Autowired AssignmentRepository assignments;
  @Autowired ObjectMapper json;
  @MockBean JwtDecoder decoder;
  @MockBean ParcelSource source;

  @BeforeEach void setup() throws Exception {
    http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    jdbc.execute("TRUNCATE dispatch.proof_of_delivery, dispatch.assignment_audit, dispatch.outbox_event, dispatch.delivery_assignment");
    for (String role : List.of("CUSTOMER", "MERCHANT", "COURIER")) token(role.toLowerCase(), role, false);
    token(ROLE_ONLY, "DISPATCHER", false);
    token("dispatcher", "DISPATCHER", true);
    token("courier-one", "COURIER", false);
    token("courier-two", "COURIER", false);
    parcelPort.respond(200, json.writeValueAsString(new ParcelSource.Parcel(
        PARCEL, "ZD092", "Pickup", "Drop-off", Instant.parse("2026-11-01T12:00:00Z"))));
    // Preserve the existing source-port guarantee without claiming a real S2 integration.
    doAnswer(call -> {
      UUID id = call.getArgument(0);
      String bearer = call.getArgument(1);
      Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      try {
        var parcel = RestClient.create(parcelPort.baseUrl()).get().uri("/fixture/parcels/{id}", id)
            .headers(headers -> headers.setBearerAuth(bearer)).retrieve().body(ParcelSource.Parcel.class);
        return action.apply(parcel);
      } catch (RestClientResponseException error) {
        throw new DispatchException(HttpStatus.valueOf(error.getStatusCode().value()),
            "FIXTURE_PARCEL_GUARD_REJECTED", "The test parcel guard rejected the operation.");
      }
    }).when(source).withAssignedParcel(any(), anyString(), any());
    assignments.insert(PARCEL, FIRST, "original-dispatcher");
    identity.respond(200, json.writeValueAsString(SECOND));
  }

  @AfterAll static void closeStubs() { identity.close(); parcelPort.close(); }

  @Test void roleOnlyReassignmentMovesVisibilityWorkloadAndWritesAuditAndOutbox() throws Exception {
    assertVisible("courier-one", FIRST, true);
    assertVisible("courier-two", SECOND, false);
    assertWorkload(1, 0);
    identity.respond(200, json.writeValueAsString(SECOND));
    var response = reassign(PARCEL, SECOND.id(), ROLE_ONLY);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(json.readTree(response.getBody()).path("courierId").asText()).isEqualTo(SECOND.id().toString());
    assertThat(assignments.find(PARCEL).orElseThrow().assignedBy()).isEqualTo("subject-" + ROLE_ONLY);
    assertVisible("courier-one", FIRST, false);
    assertVisible("courier-two", SECOND, true);
    assertWorkload(0, 1);
    assertLifecycle("REASSIGNED", "delivery.reassigned", FIRST.id(), SECOND.id());
    assertThat(parcelPort.lastAuthorization()).isEqualTo("Bearer " + ROLE_ONLY);
  }

  @Test void roleOnlyUnassignmentRemovesVisibilityAndWorkloadAndWritesCancellation() throws Exception {
    var response = request(HttpMethod.DELETE, assignmentPath(PARCEL), ROLE_ONLY, null);
    assertThat(response.getStatusCode().value()).isEqualTo(204);
    assertThat(assignments.find(PARCEL)).isEmpty();
    assertVisible("courier-one", FIRST, false);
    assertVisible("courier-two", SECOND, false);
    identity.respond(200, json.writeValueAsString(FIRST));
    assertThat(request(HttpMethod.GET, DELIVERIES + "/" + PARCEL, "courier-one", null)
        .getStatusCode().value()).isEqualTo(404);
    assertWorkload(0, 0);
    assertLifecycle("CANCELLED", "delivery.cancelled", FIRST.id(), null);
  }

  @Test void sameCourierConflictPreservesAssignmentProofAuditAndOutbox() throws Exception {
    identity.respond(200, json.writeValueAsString(FIRST));
    var before = snapshot();
    assertProblem(reassign(PARCEL, FIRST.id(), ROLE_ONLY), 409, "COURIER_ALREADY_ASSIGNED");
    assertThat(snapshot()).isEqualTo(before);
  }

  @Test void missingAssignmentReassignmentAndUnassignmentLeaveAllTablesUnchanged() throws Exception {
    var before = snapshot();
    int identityReads = identity.requests();
    assertProblem(reassign(UNASSIGNED, SECOND.id(), ROLE_ONLY), 404, "DELIVERY_NOT_ASSIGNED");
    assertProblem(request(HttpMethod.DELETE, assignmentPath(UNASSIGNED), ROLE_ONLY, null), 404, "DELIVERY_NOT_ASSIGNED");
    assertThat(identity.requests()).isEqualTo(identityReads);
    assertThat(snapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @CsvSource({"customer,403", "merchant,403", "courier,403", "anonymous,401"})
  void rejectedRolesCannotReassignOrUnassignAndDoNotReachDependencies(String caller, int status) throws Exception {
    String token = caller.equals("anonymous") ? null : caller;
    var before = snapshot();
    int identityReads = identity.requests();
    int parcelReads = parcelPort.requests();
    assertThat(reassign(PARCEL, SECOND.id(), token).getStatusCode().value()).isEqualTo(status);
    assertThat(request(HttpMethod.DELETE, assignmentPath(PARCEL), token, null).getStatusCode().value()).isEqualTo(status);
    assertThat(snapshot()).isEqualTo(before);
    assertThat(identity.requests()).isEqualTo(identityReads);
    assertThat(parcelPort.requests()).isEqualTo(parcelReads);
  }

  @ParameterizedTest
  @CsvSource({"false,ACTIVE", "true,DISABLED"})
  void newlyIneligibleReplacementLeavesOwnershipWorkloadAuditAndOutboxUnchanged(boolean active, String accountStatus) throws Exception {
    identity.respond(200, json.writeValueAsString(new IdentityCourierClient.Courier(
        SECOND.id(), SECOND.userId(), SECOND.name(), active, accountStatus)));
    var before = snapshot();
    assertProblem(reassign(PARCEL, SECOND.id(), ROLE_ONLY), 409, "COURIER_INELIGIBLE");
    assertThat(snapshot()).isEqualTo(before);
    assertVisible("courier-one", FIRST, true);
    assertWorkload(1, 0);
  }

  @ParameterizedTest
  @ValueSource(ints = {409, 503})
  void assignedParcelGuardFailurePreservesAllStateForBothMutations(int status) throws Exception {
    parcelPort.respond(status, "{}");
    var before = snapshot();
    int identityReads = identity.requests();
    assertProblem(reassign(PARCEL, SECOND.id(), ROLE_ONLY), status, "FIXTURE_PARCEL_GUARD_REJECTED");
    assertProblem(request(HttpMethod.DELETE, assignmentPath(PARCEL), ROLE_ONLY, null), status, "FIXTURE_PARCEL_GUARD_REJECTED");
    assertThat(snapshot()).isEqualTo(before);
    assertThat(identity.requests()).isEqualTo(identityReads);
  }

  private void token(String value, String role, boolean permissions) {
    var builder = Jwt.withTokenValue(value).header("alg", "test").subject("subject-" + value)
        .claim("realm_access", Map.of("roles", List.of(role)))
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
    if (permissions) builder.claim("permissions", List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN"));
    when(decoder.decode(value)).thenReturn(builder.build());
  }

  private ResponseEntity<String> reassign(UUID parcel, UUID courier, String token) {
    return request(HttpMethod.POST, assignmentPath(parcel) + "/reassign", token, Map.of("courierId", courier));
  }

  private static String assignmentPath(UUID parcel) { return DISPATCH + "/assignments/" + parcel; }

  private ResponseEntity<String> request(HttpMethod method, String path, String token, Object body) {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (token != null) headers.setBearerAuth(token);
    return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  private void assertVisible(String token, IdentityCourierClient.Courier courier, boolean visible) throws Exception {
    identity.respond(200, json.writeValueAsString(courier));
    var response = request(HttpMethod.GET, DELIVERIES, token, null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    var items = json.readTree(response.getBody());
    assertThat(items.size()).isEqualTo(visible ? 1 : 0);
    if (visible) {
      assertThat(items.get(0).path("parcelId").asText()).isEqualTo(PARCEL.toString());
      assertThat(request(HttpMethod.GET, DELIVERIES + "/" + PARCEL, token, null).getStatusCode().value()).isEqualTo(200);
    } else if (assignments.exists(PARCEL)) {
      assertProblem(request(HttpMethod.GET, DELIVERIES + "/" + PARCEL, token, null), 403, "DELIVERY_FORBIDDEN");
    }
  }

  private void assertWorkload(long first, long second) throws Exception {
    identity.respond(200, json.writeValueAsString(List.of(FIRST, SECOND)));
    var response = request(HttpMethod.GET, DISPATCH + "/workload", "dispatcher", null);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    var counts = new java.util.HashMap<String, Long>();
    json.readTree(response.getBody()).path("items").forEach(item ->
        counts.put(item.path("courierId").asText(), item.path("assignmentCount").asLong()));
    assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(FIRST.id().toString(), first, SECOND.id().toString(), second));
  }

  private void assertLifecycle(String action, String eventType, UUID oldCourier, UUID newCourier) throws Exception {
    var audit = jdbc.queryForMap("SELECT * FROM dispatch.assignment_audit WHERE action = ?", action);
    assertThat(audit.get("parcel_id")).isEqualTo(PARCEL);
    assertThat(audit.get("old_courier_profile_id")).isEqualTo(oldCourier);
    assertThat(audit.get("new_courier_profile_id")).isEqualTo(newCourier);
    assertThat(audit.get("actor_subject")).isEqualTo("subject-" + ROLE_ONLY);
    var event = jdbc.queryForMap("SELECT * FROM dispatch.outbox_event WHERE event_type = ?", eventType);
    assertThat(event.get("aggregate_id")).isEqualTo(PARCEL);
    JsonNode envelope = json.readTree(event.get("payload").toString());
    assertThat(envelope.path("eventId").asText()).isEqualTo(event.get("event_id").toString());
    assertThat(envelope.path("eventType").asText()).isEqualTo(eventType);
    assertThat(envelope.path("aggregateId").asText()).isEqualTo(PARCEL.toString());
    assertThat(envelope.path("occurredAt").asText()).isNotBlank();
    assertThat(envelope.path("payload").path("action").asText()).isEqualTo(action);
    assertThat(envelope.path("payload").path("oldCourierId").asText()).isEqualTo(oldCourier.toString());
    if (newCourier == null) assertThat(envelope.path("payload").path("newCourierId").isNull()).isTrue();
    else assertThat(envelope.path("payload").path("newCourierId").asText()).isEqualTo(newCourier.toString());
    assertThat(envelope.path("payload").path("actorSubject").asText()).isEqualTo("subject-" + ROLE_ONLY);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.assignment_audit", Long.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM dispatch.outbox_event", Long.class)).isEqualTo(2);
  }

  private void assertProblem(ResponseEntity<String> response, int status, String code) throws Exception {
    assertThat(response.getStatusCode().value()).isEqualTo(status);
    assertThat(json.readTree(response.getBody()).path("code").asText()).isEqualTo(code);
  }

  private Map<String, List<Map<String, Object>>> snapshot() {
    return Map.of(
        "assignments", jdbc.queryForList("SELECT * FROM dispatch.delivery_assignment ORDER BY parcel_id"),
        "proofs", jdbc.queryForList("SELECT * FROM dispatch.proof_of_delivery ORDER BY parcel_id"),
        "audit", jdbc.queryForList("SELECT * FROM dispatch.assignment_audit ORDER BY audit_id"),
        "outbox", jdbc.queryForList("SELECT * FROM dispatch.outbox_event ORDER BY event_id"));
  }
}
