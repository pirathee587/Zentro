package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.zentro.parcelpricing.integration.support.OutboxFaultTriggers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ZD-062: a merchant order (its parcel row) and its parcel.created outbox event (source MERCHANT_ORDER) are committed
 * together or not at all. Tests only; the merchant order code (#23) is not changed. Failures are injected with real
 * PostgreSQL triggers ({@link OutboxFaultTriggers}); S1 approval is mocked. Never touches the shared database;
 * skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class MerchantOrderOutboxAtomicityIntegrationTest {
  private static final String BODY = """
      {
        "externalOrderRef": "%s",
        "sender": {"name": "Atomic Store", "phone": "0112345678", "address": "12 Main Street, Colombo", "city": "Colombo"},
        "recipient": {"name": "Atomic Customer", "phone": "+94771234567", "address": "45 Temple Road, Kandy", "city": "Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Electronics", "size": "MEDIUM"},
        "destinationType": "OTHER_DISTRICT"
      }
      """;

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @MockBean IdentityMerchantClient identityClient; // approval check passes (does nothing)
  @MockBean JwtDecoder jwtDecoder;

  private MockHttpServletResponse order(String merchantId, String externalRef) throws Exception {
    return orderWithBody(merchantId, BODY.formatted(externalRef));
  }

  private MockHttpServletResponse orderWithBody(String merchantId, String body) throws Exception {
    return mvc.perform(post("/api/v1/merchant/orders")
            .with(jwt().jwt(j -> j.subject(merchantId).tokenValue("merchant-token"))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
            .contentType(MediaType.APPLICATION_JSON).content(body))
        .andReturn().getResponse();
  }

  private int parcels(String merchantId) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel WHERE merchant_id = ?", Integer.class, merchantId);
  }

  /** parcel.created events whose payload names this merchant (the event is the only place merchantId is published). */
  private int events(String merchantId) {
    return jdbc.queryForObject("""
        SELECT COUNT(*) FROM parcel.outbox_event
         WHERE event_type = 'parcel.created' AND payload->'payload'->>'merchantId' = ?
        """, Integer.class, merchantId);
  }

  private static String merchant() {
    return "atomic-merchant-" + UUID.randomUUID();
  }

  @Test
  @DisplayName("Outbox insert fails: 503, no parcel and no event for the order")
  void outboxInsertFailure() throws Exception {
    String merchantId = merchant();

    MockHttpServletResponse response;
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectInsert(jdbc, "parcel.outbox_event")) {
      response = order(merchantId, "ORD-ATOMIC-1");
    }

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).doesNotContain(OutboxFaultTriggers.MESSAGE);
    assertThat(parcels(merchantId)).isZero();
    assertThat(events(merchantId)).isZero();
  }

  @Test
  @DisplayName("Both inserts run but the commit fails: 503 and nothing is kept")
  void commitFailureKeepsNothing() throws Exception {
    String merchantId = merchant();

    MockHttpServletResponse response;
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectAtCommit(jdbc, "parcel.outbox_event")) {
      response = order(merchantId, "ORD-ATOMIC-2");
    }

    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(response.getContentAsString()).doesNotContain("Atomic Customer", "+94771234567");
    assertThat(parcels(merchantId)).isZero();
    assertThat(events(merchantId)).isZero();
  }

  @Test
  @DisplayName("After a failed attempt the same externalOrderRef creates the order once, with exactly one event")
  void retryAfterFailureCreatesOnce() throws Exception {
    String merchantId = merchant();
    try (OutboxFaultTriggers fault = OutboxFaultTriggers.rejectInsert(jdbc, "parcel.outbox_event")) {
      assertThat(order(merchantId, "ORD-ATOMIC-3").getStatus()).isEqualTo(503);
    }

    assertThat(order(merchantId, "ORD-ATOMIC-3").getStatus()).isEqualTo(201);
    assertThat(order(merchantId, "ORD-ATOMIC-3").getStatus()).isEqualTo(200);

    assertThat(parcels(merchantId)).isEqualTo(1);
    assertThat(events(merchantId)).isEqualTo(1);
  }

  @Test
  @DisplayName("Six parallel submissions of one externalOrderRef create one parcel and one event")
  void parallelSubmissionsCreateOneEvent() throws Exception {
    String merchantId = merchant();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    List<Integer> statuses = new ArrayList<>();
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        Callable<Integer> call = () -> {
          start.await();
          return order(merchantId, "ORD-ATOMIC-PARALLEL").getStatus();
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      for (Future<Integer> result : results) {
        statuses.add(result.get());
      }
    } finally {
      pool.shutdownNow();
    }

    assertThat(statuses).containsOnlyOnce(201);
    assertThat(statuses).filteredOn(s -> s == 200).hasSize(5);
    assertThat(parcels(merchantId)).isEqualTo(1);
    assertThat(events(merchantId)).isEqualTo(1);
  }

  @Test
  @DisplayName("A rejected order (422) and a conflicting replay (409) write no event")
  void rejectedOrdersWriteNoEvent() throws Exception {
    String merchantId = merchant();
    String invalid = BODY.formatted("ORD-ATOMIC-BAD").replace("\"weightKg\": 2.0", "\"weightKg\": -1");

    assertThat(orderWithBody(merchantId, invalid).getStatus()).isEqualTo(422);
    assertThat(events(merchantId)).isZero();

    assertThat(order(merchantId, "ORD-ATOMIC-4").getStatus()).isEqualTo(201);
    String changed = BODY.formatted("ORD-ATOMIC-4").replace("Atomic Customer", "Someone Else");
    assertThat(orderWithBody(merchantId, changed).getStatus()).isEqualTo(409);

    assertThat(parcels(merchantId)).isEqualTo(1);
    assertThat(events(merchantId)).isEqualTo(1);
  }
}
