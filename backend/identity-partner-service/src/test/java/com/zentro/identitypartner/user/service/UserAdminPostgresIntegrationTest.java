package com.zentro.identitypartner.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.identitypartner.user.dto.AdminUpdateUserRequest;
import com.zentro.identitypartner.user.dto.UpdateUserProfileRequest;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ZD-042 on a real PostgreSQL (Testcontainers postgres:16-alpine, Flyway V1..V6, real transactions and row locks).
 * Never touches the shared database: the datasource is always the test container. Skipped when Docker is not
 * available.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class UserAdminPostgresIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
    registry.add("grpc.server.enabled", () -> "false");
  }

  @Autowired UserProfileService service;
  @Autowired JdbcTemplate jdbc;
  @MockBean JwtDecoder jwtDecoder;

  private Jwt dispatcherJwt;
  private UserProfile dispatcher;

  private static Jwt jwt(String subject, String name, String phone, String... roles) {
    return Jwt.withTokenValue("token-" + subject).header("alg", "RS256").subject(subject)
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
        .claim("email", subject + "@zentro.test").claim("name", name).claim("phone_number", phone)
        .claim("realm_access", Map.of("roles", List.of(roles))).build();
  }

  /** Creates (or refreshes) the user in identity.app_user exactly as GET /api/v1/users/me does. */
  private UserProfile user(String subject, String name, String phone, String... roles) {
    return service.current(jwt(subject, name, phone, roles));
  }

  private List<Map<String, Object>> auditRows(UUID target) {
    return jdbc.queryForList("""
        SELECT actor_user_id, actor_subject, target_user_id, action, changed_fields, old_status, new_status, reason
          FROM identity.user_admin_audit WHERE target_user_id = ? ORDER BY occurred_at, id
        """, target);
  }

  private static List<String> fields(Map<String, Object> row) throws Exception {
    return Arrays.asList((String[]) ((Array) row.get("changed_fields")).getArray());
  }

  @BeforeEach
  void signInDispatcher() {
    String subject = "dispatcher-" + UUID.randomUUID();
    dispatcherJwt = jwt(subject, "Dispatcher", "0771112222", "DISPATCHER", "CUSTOMER");
    dispatcher = service.current(dispatcherJwt);
  }

  @Test
  @DisplayName("Flyway applies V6: the audit table and its immutability triggers exist")
  void migrationApplied() {
    assertThat(jdbc.queryForObject(
        "SELECT MAX(version::int) FROM identity.flyway_schema_history WHERE success", Integer.class)).isGreaterThanOrEqualTo(6);
    assertThat(jdbc.queryForList("""
        SELECT tgname FROM pg_trigger WHERE tgrelid = 'identity.user_admin_audit'::regclass AND NOT tgisinternal
        """, String.class)).containsExactlyInAnyOrder("user_admin_audit_no_update_delete", "user_admin_audit_no_truncate");
  }

  @Test
  @DisplayName("A status change writes exactly one audit row with field names, statuses and reason, and no personal data")
  void statusChangeWritesOneRow() throws Exception {
    UserProfile customer = user("cust-" + UUID.randomUUID(), "Kamal Silva", "0772223333", "CUSTOMER");

    UserProfile result = service.adminUpdate(customer.id(), dispatcherJwt,
        new AdminUpdateUserRequest("Kamal Silva", "0772223333", "disabled", "Repeated chargebacks"));

    assertThat(result.accountStatus()).isEqualTo("DISABLED");
    assertThat(result.enabled()).isFalse();
    List<Map<String, Object>> rows = auditRows(customer.id());
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.get(0);
    assertThat(row.get("action")).isEqualTo("STATUS_CHANGED");
    assertThat(fields(row)).containsExactly("accountStatus");
    assertThat(row).containsEntry("old_status", "ACTIVE").containsEntry("new_status", "DISABLED")
        .containsEntry("reason", "Repeated chargebacks").containsEntry("actor_user_id", dispatcher.id())
        .containsEntry("actor_subject", dispatcherJwt.getSubject());
    assertThat(row.toString()).doesNotContain("Kamal", "0772223333", "@zentro.test");
  }

  @Test
  @DisplayName("Name and phone changes are stored (phone normalised) and audited as PROFILE_UPDATED")
  void profileChange() throws Exception {
    UserProfile customer = user("cust-" + UUID.randomUUID(), "Kamal", "0772223333", "CUSTOMER");

    UserProfile result = service.adminUpdate(customer.id(), dispatcherJwt,
        new AdminUpdateUserRequest(" Kamal Perera ", "+94 77-123-4567", null));

    assertThat(result.name()).isEqualTo("Kamal Perera");
    assertThat(result.phone()).isEqualTo("+94771234567");
    Map<String, Object> row = auditRows(customer.id()).get(0);
    assertThat(row.get("action")).isEqualTo("PROFILE_UPDATED");
    assertThat(fields(row)).containsExactly("name", "phone");
    assertThat(row).containsEntry("old_status", "ACTIVE").containsEntry("new_status", "ACTIVE");
  }

  @Test
  @DisplayName("Sending the same update twice writes one row; the second call changes nothing")
  void repeatedUpdateIsNoOp() {
    UserProfile customer = user("cust-" + UUID.randomUUID(), "Kamal", "0772223333", "CUSTOMER");
    AdminUpdateUserRequest suspend = new AdminUpdateUserRequest(null, null, "SUSPENDED", "Investigation");

    service.adminUpdate(customer.id(), dispatcherJwt, suspend);
    Object updatedAt = jdbc.queryForObject("SELECT updated_at FROM identity.app_user WHERE id = ?", Object.class, customer.id());
    UserProfile second = service.adminUpdate(customer.id(), dispatcherJwt, suspend);

    assertThat(second.accountStatus()).isEqualTo("SUSPENDED");
    assertThat(auditRows(customer.id())).hasSize(1);
    assertThat(jdbc.queryForObject("SELECT updated_at FROM identity.app_user WHERE id = ?", Object.class, customer.id()))
        .isEqualTo(updatedAt);
  }

  @Test
  @DisplayName("Audit rows cannot be updated, deleted or truncated")
  void auditRowsAreImmutable() {
    UserProfile customer = user("cust-" + UUID.randomUUID(), "Kamal", "0772223333", "CUSTOMER");
    service.adminUpdate(customer.id(), dispatcherJwt, new AdminUpdateUserRequest(null, null, "DISABLED"));

    assertThatThrownBy(() -> jdbc.update(
        "UPDATE identity.user_admin_audit SET reason = 'changed' WHERE target_user_id = ?", customer.id()))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("DELETE FROM identity.user_admin_audit WHERE target_user_id = ?", customer.id()))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.execute("TRUNCATE identity.user_admin_audit"))
        .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    assertThat(auditRows(customer.id())).hasSize(1).first().satisfies(row -> assertThat(row.get("reason")).isNull());
  }

  @Test
  @DisplayName("A rejected change (own status, protected rule) changes nothing and writes no audit row")
  void rejectedChangeWritesNothing() {
    assertThatThrownBy(() -> service.adminUpdate(dispatcher.id(), dispatcherJwt,
        new AdminUpdateUserRequest(null, null, "DISABLED")))
        .isInstanceOfSatisfying(InvalidUserInputException.class, e -> assertThat(e.code()).isEqualTo("SELF_STATUS_CHANGE"));
    UserProfile customer = user("cust-" + UUID.randomUUID(), "Kamal", "0772223333", "CUSTOMER");
    assertThatThrownBy(() -> service.adminUpdate(customer.id(), dispatcherJwt,
        new AdminUpdateUserRequest(null, "12345", "DISABLED")))
        .isInstanceOf(InvalidUserInputException.class);

    assertThat(auditRows(dispatcher.id())).isEmpty();
    assertThat(auditRows(customer.id())).isEmpty();
    assertThat(service.getById(customer.id()).accountStatus()).isEqualTo("ACTIVE");
    assertThat(service.getById(dispatcher.id()).accountStatus()).isEqualTo("ACTIVE");
  }

  @Test
  @DisplayName("Concurrent status changes on one user are both audited, each with the status it really replaced")
  void concurrentUpdatesAreSerialised() throws Exception {
    UserProfile customer = user("cust-" + UUID.randomUUID(), "Kamal", "0772223333", "CUSTOMER");
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Future<UserProfile>> results = new ArrayList<>();
      for (String status : List.of("SUSPENDED", "DISABLED")) {
        Callable<UserProfile> call = () -> {
          start.await();
          return service.adminUpdate(customer.id(), dispatcherJwt, new AdminUpdateUserRequest(null, null, status));
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      for (Future<UserProfile> result : results) {
        result.get();
      }
    } finally {
      pool.shutdownNow();
    }

    List<Map<String, Object>> rows = new ArrayList<>(auditRows(customer.id()));
    assertThat(rows).hasSize(2);
    rows.sort(Comparator.comparing(r -> "ACTIVE".equals(r.get("old_status")) ? 0 : 1));
    assertThat(rows.get(0).get("old_status")).isEqualTo("ACTIVE");
    assertThat(rows.get(1).get("old_status")).isEqualTo(rows.get(0).get("new_status"));
    assertThat(service.getById(customer.id()).accountStatus()).isEqualTo(rows.get(1).get("new_status"));
  }

  @Test
  @DisplayName("A dispatcher editing another user via /api/v1/users/{id} is audited too (D5)")
  void crossUserProfilePathAudited() throws Exception {
    UserProfile customer = user("cust-" + UUID.randomUUID(), "Kamal", "0772223333", "CUSTOMER");

    service.updateByIdForCaller(dispatcherJwt, customer.id(), new UpdateUserProfileRequest("Kamal P", null));

    Map<String, Object> row = auditRows(customer.id()).get(0);
    assertThat(row.get("action")).isEqualTo("PROFILE_UPDATED");
    assertThat(fields(row)).containsExactly("name");
  }

  @Test
  @DisplayName("List filters: valid status only, and % and _ in search match literally")
  void listFiltersAndEscaping() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    UserProfile percent = user("p-" + tag, "Store 50% Off " + tag, "0770000001", "CUSTOMER");
    user("q-" + tag, "Store 500 Off " + tag, "0770000002", "CUSTOMER");
    UserProfile underscore = user("u-" + tag, "a_b " + tag, "0770000003", "CUSTOMER");
    user("x-" + tag, "axb " + tag, "0770000004", "CUSTOMER");
    service.adminUpdate(underscore.id(), dispatcherJwt, new AdminUpdateUserRequest(null, null, "SUSPENDED"));

    assertThat(service.listUsers(null, "50% off " + tag)).extracting(UserProfile::id).containsExactly(percent.id());
    assertThat(service.listUsers(null, "a_b " + tag)).extracting(UserProfile::id).containsExactly(underscore.id());
    assertThat(service.listUsers("suspended", tag)).extracting(UserProfile::id).containsExactly(underscore.id());
    assertThat(service.listUsers("ACTIVE", tag)).hasSize(3);
    assertThatThrownBy(() -> service.listUsers("ARCHIVED", null)).isInstanceOf(InvalidUserInputException.class);
  }
}
