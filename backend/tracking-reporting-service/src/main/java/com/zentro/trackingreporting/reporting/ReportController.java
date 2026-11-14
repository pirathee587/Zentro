package com.zentro.trackingreporting.reporting;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports/deliveries")
@PreAuthorize("hasRole('DISPATCHER')")
public class ReportController {
  private final ReportService service;

  public ReportController(ReportService service) { this.service = service; }

  @GetMapping("/daily")
  public DailyDeliveryReport daily(@RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    return service.daily(date("from", from), date("to", to));
  }

  @GetMapping("/summary")
  public DeliverySummaryReport summary(@RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "to", required = false) String to) {
    return service.summary(date("from", from), date("to", to));
  }

  private LocalDate date(String field, String value) {
    if (value == null) throw new ReportValidationException(field, field + " is required.");
    if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
      throw new ReportValidationException(field, field + " must be a valid YYYY-MM-DD date.");
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new ReportValidationException(field, field + " must be a valid YYYY-MM-DD date.");
    }
  }
}
