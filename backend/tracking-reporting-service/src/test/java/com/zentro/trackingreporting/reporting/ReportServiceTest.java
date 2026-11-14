package com.zentro.trackingreporting.reporting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReportServiceTest {
  private final ReportQueryRepository repository = mock(ReportQueryRepository.class);
  private final LocalDate today = LocalDate.of(2026, 11, 14);
  private ReportService service;

  @BeforeEach void setup() {
    service = new ReportService(repository, Clock.fixed(Instant.parse("2026-11-13T18:30:00Z"), ZoneOffset.UTC));
  }

  @Test void rejectsInvalidRangesBeforeQuerying() {
    invalid(null, today, "from");
    invalid(today, null, "to");
    invalid(today, today.minusDays(1), "from");
    invalid(today.minusDays(92), today, "to");
    invalid(today, today.plusDays(1), "to");
    verifyNoInteractions(repository);
  }

  private void invalid(LocalDate from, LocalDate to, String field) {
    assertThatThrownBy(() -> service.daily(from, to)).isInstanceOfSatisfying(
        ReportValidationException.class, error -> assertThat(error.field()).isEqualTo(field));
  }

  @Test void acceptsExactly92InclusiveDaysAndColomboToday() {
    when(repository.daily(today.minusDays(91), today)).thenReturn(List.of());
    var report = service.daily(today.minusDays(91), today);
    assertThat(report.days()).hasSize(92);
    assertThat(report.days().getLast().day()).isEqualTo(today);
  }

  @Test void preservesViewValuesAndFillsMissingDaysInOrder() {
    var first = new DailyDeliveryRow(today.minusDays(2), 4, 1, 0, 3, BigDecimal.ONE);
    var middle = new DailyDeliveryRow(today.minusDays(1), 0, 0, 0, 3, null);
    when(repository.daily(today.minusDays(2), today)).thenReturn(List.of(middle, first));
    var report = service.daily(today.minusDays(2), today);
    assertThat(report.timezone()).isEqualTo("Asia/Colombo");
    assertThat(report.days()).containsExactly(first, middle, new DailyDeliveryRow(today, 0, 0, 0, 0, null));
    assertThatThrownBy(() -> report.days().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test void summaryUsesTotalsRatherThanAverageOfDailyRates() {
    when(repository.daily(today.minusDays(1), today)).thenReturn(List.of(
        new DailyDeliveryRow(today.minusDays(1), 10, 1, 0, 9, BigDecimal.ONE),
        new DailyDeliveryRow(today, 2, 1, 8, 2, new BigDecimal("0.111111"))));
    var summary = service.summary(today.minusDays(1), today);
    assertThat(summary.created()).isEqualTo(12);
    assertThat(summary.delivered()).isEqualTo(2);
    assertThat(summary.failed()).isEqualTo(8);
    assertThat(summary.successRate()).isEqualByComparingTo("0.2");
  }

  @Test void emptyRangeHasZeroTotalsAndNullRate() {
    when(repository.daily(today, today)).thenReturn(List.of());
    assertThat(service.summary(today, today)).isEqualTo(
        new DeliverySummaryReport(today, today, "Asia/Colombo", 0, 0, 0, null));
  }
}
