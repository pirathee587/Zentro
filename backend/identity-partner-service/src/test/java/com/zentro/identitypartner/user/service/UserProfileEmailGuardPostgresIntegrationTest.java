package com.zentro.identitypartner.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.identitypartner.user.exception.AccountIdentityConflictException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * First-sign-in email guard on a real PostgreSQL (Testcontainers, Flyway migrations, real advisory locks).
 * Never touches the shared database: the datasource is always the test container. Skipped without Docker.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class UserProfileEmailGuardPostgresIntegrationTest {
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

  private static Jwt jwt(String subject, String email) {
    return Jwt.withTokenValue("token-" + subject).header("alg", "RS256").subject(subject)
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
        .claim("email", email).claim("name", "Guard Test")
        .claim("realm_access", Map.of("roles", List.of("CUSTOMER"))).build();
  }

  private int profilesFor(String email) {
    return jdbc.queryForObject("SELECT count(*) FROM identity.app_user WHERE LOWER(email) = LOWER(?)", Integer.class, email);
  }

  @Test
  void secondIdentityForSameEmailIsRefusedAndOriginalKeepsWorking() {
    service.current(jwt("kc-original", "owner@gmail.com"));

    assertThatThrownBy(() -> service.current(jwt("kc-google-elsewhere", "Owner@GMAIL.com")))
        .isInstanceOf(AccountIdentityConflictException.class);

    assertThat(profilesFor("owner@gmail.com")).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM identity.app_user WHERE keycloak_subject = 'kc-google-elsewhere'", Integer.class)).isZero();
    service.current(jwt("kc-original", "owner@gmail.com"));
    assertThat(profilesFor("owner@gmail.com")).isEqualTo(1);
  }

  @Test
  void concurrentFirstSignInsForOneEmailCreateExactlyOneProfile() throws Exception {
    int callers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < callers; i++) {
        String subject = "kc-race-" + i;
        Callable<Boolean> call = () -> {
          start.await();
          try {
            service.current(jwt(subject, "race@gmail.com"));
            return true;
          } catch (AccountIdentityConflictException refused) {
            return false;
          }
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      int created = 0;
      for (Future<Boolean> result : results) if (result.get()) created++;
      assertThat(created).isEqualTo(1);
      assertThat(profilesFor("race@gmail.com")).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
