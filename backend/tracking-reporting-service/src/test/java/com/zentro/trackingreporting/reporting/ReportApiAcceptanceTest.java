package com.zentro.trackingreporting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ReportApiAcceptanceTest extends ReportingPostgresTestSupport {
  private static final String ROOT = "/api/v1/reports/deliveries/";
  @Autowired MockMvc mvc;

  @BeforeEach void tokens() {
    when(decoder.decode(anyString())).thenAnswer(invocation -> {
      String token = invocation.getArgument(0);
      if (token.equals("invalid")) throw new BadJwtException("Invalid synthetic token");
      return Jwt.withTokenValue(token).header("alg", "RS256").subject("synthetic-report-user")
          .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(300))
          .claim("realm_access", Map.of("roles", token.equals("ROLELESS") ? List.of() : List.of(token)))
          .build();
    });
  }

  @ParameterizedTest
  @CsvSource({"daily,CUSTOMER", "daily,MERCHANT", "daily,COURIER", "daily,SERVICE", "daily,ROLELESS",
      "summary,CUSTOMER", "summary,MERCHANT", "summary,COURIER", "summary,SERVICE", "summary,ROLELESS"})
  void onlyDispatcherCanReadReports(String endpoint, String role) throws Exception {
    mvc.perform(get(ROOT + endpoint).param("from", "2026-11-01").param("to", "2026-11-05")
        .header("Authorization", "Bearer " + role)).andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(strings = {"daily", "summary"})
  void absentAndInvalidTokensAreUnauthorized(String endpoint) throws Exception {
    mvc.perform(get(ROOT + endpoint).param("from", "2026-11-01").param("to", "2026-11-05"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get(ROOT + endpoint).header("Authorization", "Bearer invalid")
        .param("from", "2026-11-01").param("to", "2026-11-05"))
        .andExpect(status().isUnauthorized());
  }

  static Stream<Arguments> invalidDates() {
    String tomorrow = LocalDate.now(ZoneId.of("Asia/Colombo")).plusDays(1).toString();
    var cases = List.of(
        new String[]{null, "2026-11-01", "from"}, new String[]{"2026-11-01", null, "to"},
        new String[]{"", "2026-11-01", "from"}, new String[]{"2026-11-01", "", "to"},
        new String[]{"bad-date", "2026-11-01", "from"}, new String[]{"2026-11-01", "2026-02-30", "to"},
        new String[]{"2026-1-01", "2026-11-01", "from"},
        new String[]{"2026-11-02", "2026-11-01", "from"},
        new String[]{"2026-01-01", "2026-04-03", "to"}, new String[]{tomorrow, tomorrow, "to"});
    return Stream.of("daily", "summary").flatMap(endpoint -> cases.stream()
        .map(values -> Arguments.of(endpoint, values[0], values[1], values[2])));
  }

  @ParameterizedTest
  @MethodSource("invalidDates")
  void invalidDatesReturnScopedFieldErrors(String endpoint, String from, String to, String field) throws Exception {
    var request = get(ROOT + endpoint).header("Authorization", "Bearer DISPATCHER");
    if (from != null) request.param("from", from);
    if (to != null) request.param("to", to);
    mvc.perform(request).andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
        .andExpect(jsonPath("$.instance").value(ROOT + endpoint))
        .andExpect(jsonPath("$.fieldErrors." + field).isString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"daily", "summary"})
  void emptyRangeReturnsZerosAndNullRate(String endpoint) throws Exception {
    var result = report(endpoint, "2026-11-01", "2026-11-03");
    assertThat(result.path("timezone").asText()).isEqualTo("Asia/Colombo");
    if (endpoint.equals("daily")) {
      assertThat(result.path("days").size()).isEqualTo(3);
      for (var day : result.path("days")) {
        assertThat(day.path("created").asLong()).isZero();
        assertThat(day.path("delivered").asLong()).isZero();
        assertThat(day.path("failed").asLong()).isZero();
        assertThat(day.path("active").asLong()).isZero();
        assertThat(day.path("successRate").isNull()).isTrue();
      }
    } else {
      assertThat(result.path("created").asLong()).isZero();
      assertThat(result.path("delivered").asLong()).isZero();
      assertThat(result.path("failed").asLong()).isZero();
      assertThat(result.path("successRate").isNull()).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void dateFilteredReportsReconcileWithIndependentFacts(boolean reverse) throws Exception {
    var ledger = new ArrayList<>(ExpectedDeliveryFacts.ledger());
    if (reverse) Collections.reverse(ledger);
    ledger.forEach(fact -> { apply(fact); apply(fact); });
    var expected = ExpectedDeliveryFacts.recount(ledger);
    var daily = report("daily", "2026-10-31", "2026-11-06");
    assertThat(daily.path("days").size()).isEqualTo(7);
    long created = 0, delivered = 0, failed = 0;
    for (int index = 0; index < 7; index++) {
      LocalDate day = LocalDate.of(2026, 10, 31).plusDays(index);
      var row = daily.path("days").get(index);
      assertThat(row.path("day").asText()).isEqualTo(day.toString());
      var counts = expected.getOrDefault(day, new ExpectedDeliveryFacts.Counts(0, 0, 0));
      assertThat(row.path("created").asLong()).isEqualTo(counts.created());
      assertThat(row.path("delivered").asLong()).isEqualTo(counts.delivered());
      assertThat(row.path("failed").asLong()).isEqualTo(counts.failed());
      created += counts.created(); delivered += counts.delivered(); failed += counts.failed();
    }
    assertThat(daily.path("days").get(2).path("active").asLong()).isEqualTo(1);
    assertThat(daily.path("days").get(2).path("successRate").decimalValue()).isEqualByComparingTo("1");
    assertThat(daily.path("days").get(3).path("successRate").decimalValue()).isEqualByComparingTo("0");
    var summary = report("summary", "2026-10-31", "2026-11-06");
    assertThat(summary.path("created").asLong()).isEqualTo(created);
    assertThat(summary.path("delivered").asLong()).isEqualTo(delivered);
    assertThat(summary.path("failed").asLong()).isEqualTo(failed);
    assertThat(summary.path("successRate").decimalValue()).isEqualByComparingTo("0.5");
    var filtered = report("daily", "2026-11-02", "2026-11-03");
    assertThat(filtered.path("days").size()).isEqualTo(2);
    assertThat(filtered.path("days")).isEqualTo(mapper.valueToTree(List.of(
        daily.path("days").get(2), daily.path("days").get(3))));
  }

  private JsonNode report(String endpoint, String from, String to) throws Exception {
    var response = mvc.perform(get(ROOT + endpoint).param("from", from).param("to", to)
        .header("Authorization", "Bearer DISPATCHER"))
        .andExpect(status().isOk()).andReturn().getResponse();
    return mapper.readTree(response.getContentAsString());
  }
}
