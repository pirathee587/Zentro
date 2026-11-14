package com.zentro.trackingreporting.reporting;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReportService {
  public static final String TIMEZONE = "Asia/Colombo";
  private final ReportQueryRepository repository;
  private final Clock clock;

  @Autowired
  public ReportService(ReportQueryRepository repository) {
    this(repository, Clock.system(ZoneId.of(TIMEZONE)));
  }

  ReportService(ReportQueryRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock.withZone(ZoneId.of(TIMEZONE));
  }

  public DailyDeliveryReport daily(LocalDate from, LocalDate to) {
    validate(from, to);
    var rows = repository.daily(from, to).stream()
        .collect(Collectors.toMap(DailyDeliveryRow::day, Function.identity()));
    var days = new ArrayList<DailyDeliveryRow>();
    for (var day = from; !day.isAfter(to); day = day.plusDays(1)) {
      days.add(rows.getOrDefault(day, new DailyDeliveryRow(day, 0, 0, 0, 0, null)));
    }
    return new DailyDeliveryReport(from, to, TIMEZONE, days);
  }

  public DeliverySummaryReport summary(LocalDate from, LocalDate to) {
    var days = daily(from, to).days();
    long created = days.stream().mapToLong(DailyDeliveryRow::created).sum();
    long delivered = days.stream().mapToLong(DailyDeliveryRow::delivered).sum();
    long failed = days.stream().mapToLong(DailyDeliveryRow::failed).sum();
    BigDecimal denominator = BigDecimal.valueOf(delivered).add(BigDecimal.valueOf(failed));
    BigDecimal rate = denominator.signum() == 0 ? null
        : BigDecimal.valueOf(delivered).divide(denominator, MathContext.DECIMAL128);
    return new DeliverySummaryReport(from, to, TIMEZONE, created, delivered, failed, rate);
  }

  private void validate(LocalDate from, LocalDate to) {
    if (from == null) throw new ReportValidationException("from", "from is required.");
    if (to == null) throw new ReportValidationException("to", "to is required.");
    if (from.isAfter(to)) throw new ReportValidationException("from", "from must be on or before to.");
    if (ChronoUnit.DAYS.between(from, to) >= 92)
      throw new ReportValidationException("to", "to must define a range of at most 92 inclusive days.");
    if (to.isAfter(LocalDate.now(clock)))
      throw new ReportValidationException("to", "to must not be later than today in Asia/Colombo.");
  }
}
