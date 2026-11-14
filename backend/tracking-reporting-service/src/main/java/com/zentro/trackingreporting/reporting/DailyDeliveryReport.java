package com.zentro.trackingreporting.reporting;

import java.time.LocalDate;
import java.util.List;

public record DailyDeliveryReport(LocalDate from, LocalDate to, String timezone, List<DailyDeliveryRow> days) {
  public DailyDeliveryReport {
    days = List.copyOf(days);
  }
}
