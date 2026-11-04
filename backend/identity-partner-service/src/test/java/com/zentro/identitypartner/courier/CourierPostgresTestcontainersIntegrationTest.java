package com.zentro.identitypartner.courier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.zentro.identitypartner.user.UserProfile;
import com.zentro.identitypartner.user.UserProfileService;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

class CourierPostgresTestcontainersIntegrationTest {

  private static PostgreSQLContainer<?> postgres;
  private static JdbcTemplate jdbcTemplate;
  private static CourierProfileService courierProfileService;
  private static UserProfileService userProfileService;

  private static boolean dockerChecked = false;
  private static boolean dockerAvailable = false;

  private static synchronized boolean isDockerAvailable() {
    if (!dockerChecked) {
      try {
        dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
      } catch (Throwable t) {
        dockerAvailable = false;
      }
      dockerChecked = true;
    }
    return dockerAvailable;
  }

  @BeforeAll
  static void beforeAll() {
    if (!isDockerAvailable()) {
      return;
    }

    postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("identity_db")
        .withUsername("zentro")
        .withPassword("zentro");
    postgres.start();

    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(postgres.getJdbcUrl());
    dataSource.setUsername(postgres.getUsername());
    dataSource.setPassword(postgres.getPassword());

    Flyway flyway = Flyway.configure()
        .dataSource(dataSource)
        .schemas("identity")
        .defaultSchema("identity")
        .locations("classpath:db/migration")
        .load();
    flyway.migrate();

    jdbcTemplate = new JdbcTemplate(dataSource);
    userProfileService = new UserProfileService(jdbcTemplate);
    courierProfileService = new CourierProfileService(jdbcTemplate, userProfileService);
  }

  @BeforeEach
  void checkDocker() {
    assumeTrue(
        isDockerAvailable() && postgres != null && postgres.isRunning(),
        "Docker is unavailable on host system (Docker Desktop daemon is not running). Real PostgreSQL Testcontainers integration test could not run.");
  }

  @Test
  @DisplayName("Flyway migrations V1, V2, V3, V4 apply successfully on real PostgreSQL")
  void testMigrationsApplied() {
    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'identity' AND table_name = 'courier_profile'",
        Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("Legitimate courier profile provisioning is idempotent (ON CONFLICT DO NOTHING)")
  void testCourierProvisioningAndIdempotency() {
    UUID userId = UUID.randomUUID();
    String keycloakSubject = "kc-sub-" + UUID.randomUUID();

    jdbcTemplate.update("""
        INSERT INTO identity.app_user (id, keycloak_subject, email, display_name, phone, account_status)
        VALUES (?, ?, 'courier.real@zentro.lk', 'Nimal Fernando', '+94770000001', 'ACTIVE')
        """, userId, keycloakSubject);

    CourierProfile profile1 = courierProfileService.getOrCreateProfile(userId);
    assertThat(profile1).isNotNull();
    assertThat(profile1.userId()).isEqualTo(userId);
    assertThat(profile1.active()).isTrue();

    // Idempotent duplicate provisioning
    CourierProfile profile2 = courierProfileService.getOrCreateProfile(userId);
    assertThat(profile2.id()).isEqualTo(profile1.id());

    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM identity.courier_profile WHERE app_user_id = ?",
        Integer.class, userId);
    assertThat(count).isEqualTo(1);
  }

  @Test
  @DisplayName("Foreign key cascade: deleting app_user cascades and removes courier_profile")
  void testCascadeDeletion() {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO identity.app_user (id, keycloak_subject, email, display_name, phone)
        VALUES (?, ?, 'cascade@zentro.lk', 'Cascade User', '+94770000002')
        """, userId, "kc-sub-" + UUID.randomUUID());

    courierProfileService.getOrCreateProfile(userId);

    // Delete app_user
    jdbcTemplate.update("DELETE FROM identity.app_user WHERE id = ?", userId);

    Integer count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM identity.courier_profile WHERE app_user_id = ?",
        Integer.class, userId);
    assertThat(count).isEqualTo(0);
  }

  @Test
  @DisplayName("Check constraint enforcement: invalid vehicle_type is rejected by PostgreSQL")
  void testVehicleTypeCheckConstraint() {
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO identity.app_user (id, keycloak_subject, email, display_name, phone)
        VALUES (?, ?, 'check@zentro.lk', 'Check User', '+94770000003')
        """, userId, "kc-sub-" + UUID.randomUUID());

    assertThatThrownBy(() -> {
      jdbcTemplate.update("""
          INSERT INTO identity.courier_profile (app_user_id, vehicle_type)
          VALUES (?, 'SUBMARINE')
          """, userId);
    }).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("Listing couriers uses INNER JOIN and strictly excludes arbitrary users")
  void testListCouriersExcludesNonCouriers() {
    UUID customerId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO identity.app_user (id, keycloak_subject, email, display_name, phone)
        VALUES (?, ?, 'customer.only@zentro.lk', 'Customer Only', '+94770000004')
        """, customerId, "kc-sub-" + UUID.randomUUID());

    UUID courierId = UUID.randomUUID();
    jdbcTemplate.update("""
        INSERT INTO identity.app_user (id, keycloak_subject, email, display_name, phone)
        VALUES (?, ?, 'courier.only@zentro.lk', 'Courier Only', '+94770000005')
        """, courierId, "kc-sub-" + UUID.randomUUID());
    courierProfileService.getOrCreateProfile(courierId);

    List<CourierProfile> couriers = courierProfileService.listCouriers(null, null, null, "Only");
    assertThat(couriers).extracting(CourierProfile::userId).contains(courierId).doesNotContain(customerId);
  }
}
