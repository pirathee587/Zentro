package com.zentro.trackingreporting.reporting;

public class ReportValidationException extends IllegalArgumentException {
  private final String field;

  public ReportValidationException(String field, String message) {
    super(message);
    this.field = field;
  }

  public String field() { return field; }
}
