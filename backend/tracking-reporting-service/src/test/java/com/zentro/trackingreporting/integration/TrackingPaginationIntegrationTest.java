package com.zentro.trackingreporting.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import java.util.HashSet;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TrackingPaginationIntegrationTest {
  @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", DB::getJdbcUrl);
    r.add("spring.datasource.username", DB::getUsername);
    r.add("spring.datasource.password", DB::getPassword);
    r.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
  }
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper json;
  @Autowired EntityManagerFactory emf;
  @MockBean JwtDecoder decoder;

  private com.fasterxml.jackson.databind.JsonNode page(String owner, String query) throws Exception {
    return json.readTree(mvc.perform(get("/api/v1/tracking/mine" + query)
        .with(jwt().jwt(j -> j.subject(owner)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private void seed(String owner, int rows) {
    jdbc.update("""
        INSERT INTO tracking.tracking_parcel (tracking_ref, customer_subject, sender_name, sender_phone,
          sender_address, recipient_name, recipient_phone, recipient_address, created_at)
        SELECT ? || '-' || n, ?, 'SYNTHETIC', '0000000000', 'SYNTHETIC FIXTURE',
          'SYNTHETIC', '0000000000', 'SYNTHETIC FIXTURE', '2026-01-01'::timestamptz + n * interval '1 second'
        FROM generate_series(1, ?) n
        """, owner, owner, rows);
  }

  @Test void existingPaginationBoundsAndOwnershipAcrossEveryPage() throws Exception {
    seed("page-owner", 107);
    seed("page-other", 3);
    assertThat(page("page-owner", "").path("content").size()).isEqualTo(10);
    assertThat(page("page-owner", "?size=100000").path("content").size()).isEqualTo(100);
    assertThat(page("page-owner", "?size=0&page=-1").path("size").asInt()).isEqualTo(1);
    assertThat(page("page-owner", "?size=-1").path("size").asInt()).isEqualTo(1);
    Set<String> seen = new HashSet<>();
    for (int p = 1; p <= 16; p++) {
      var first = page("page-owner", "?page=" + p + "&size=7");
      var repeat = page("page-owner", "?page=" + p + "&size=7");
      assertThat(first.path("total").asInt()).isEqualTo(107);
      var ids = new java.util.ArrayList<String>();
      var repeated = new java.util.ArrayList<String>();
      first.path("content").forEach(row -> {
        assertThat(row.path("trackingRef").asText()).startsWith("page-owner-");
        assertThat(seen.add(row.path("id").asText())).isTrue();
        ids.add(row.path("id").asText());
      });
      repeat.path("content").forEach(row -> repeated.add(row.path("id").asText()));
      assertThat(ids).isEqualTo(repeated);
    }
    assertThat(seen).hasSize(107);
    assertThat(page("page-owner", "?page=17&size=7").path("content")).isEmpty();
    assertThat(page("page-other", "?size=100").path("total").asInt()).isEqualTo(3);
    mvc.perform(get("/api/v1/tracking/mine?size=abc").with(jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/tracking/mine").with(jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_COURIER")))).andExpect(status().isForbidden());
  }

  @Test void pageEventHistoryUsesConstantQueryCount() throws Exception {
    seed("query-owner", 21);
    var statistics = emf.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    long start = System.nanoTime();
    assertThat(page("query-owner", "?size=20").path("content").size()).isEqualTo(20);
    long queries = statistics.getPrepareStatementCount();
    System.out.printf("TRACKING_QUERY_EVIDENCE rows=20 statements=%d elapsedMs=%.3f%n", queries,
        (System.nanoTime() - start) / 1_000_000d);
    assertThat(queries).as("one page query, one count, one batched history query").isLessThanOrEqualTo(3);
  }

  @Test void malformedPaginationIsSafeWithPostgresApplicationContext() throws Exception {
    for (String query : java.util.List.of("page=abc", "size=abc", "limit=abc",
        "page=2147483648", "size=-2147483649")) {
      var response = mvc.perform(get("/api/v1/tracking/mine?" + query)
          .header("X-Correlation-Id", "postgres-pagination")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isBadRequest())
          .andReturn().getResponse();
      assertThat(response.getHeader("X-Correlation-Id")).isEqualTo("postgres-pagination");
      var body = json.readTree(response.getContentAsString());
      assertThat(body.path("code").asText()).isEqualTo("INVALID_ARGUMENT");
      assertThat(body.path("detail").asText()).isEqualTo("Pagination parameters must be valid integers.");
    }
  }

  @Test void batchedHistoryRetainsEachParcelsTimelineAndExcludesOtherOwners() throws Exception {
    seed("event-owner", 2);
    seed("event-other", 1);
    jdbc.update("""
        INSERT INTO tracking.tracking_event(tracking_ref,status,title,occurred_at)
        SELECT tracking_ref,'CREATED','SYNTHETIC EARLY','2026-01-01T00:00:00Z'::timestamptz
        FROM tracking.tracking_parcel WHERE customer_subject IN ('event-owner','event-other')
        """);
    jdbc.update("""
        INSERT INTO tracking.tracking_event(tracking_ref,status,title,occurred_at)
        SELECT tracking_ref,'IN_TRANSIT','SYNTHETIC LATE','2026-01-02T00:00:00Z'::timestamptz
        FROM tracking.tracking_parcel WHERE customer_subject IN ('event-owner','event-other')
        """);
    for (var item : page("event-owner", "?size=10").path("content")) {
      assertThat(item.path("trackingRef").asText()).startsWith("event-owner-");
      assertThat(item.path("timeline")).hasSize(2);
      assertThat(item.path("timeline").get(0).path("title").asText()).isEqualTo("SYNTHETIC EARLY");
      assertThat(item.path("timeline").get(1).path("title").asText()).isEqualTo("SYNTHETIC LATE");
    }
  }
}
