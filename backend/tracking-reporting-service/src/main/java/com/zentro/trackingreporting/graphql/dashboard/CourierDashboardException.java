package com.zentro.trackingreporting.graphql.dashboard;

/** Safe machine code only; never wraps credential-bearing HTTP/gRPC exceptions. */
public class CourierDashboardException extends RuntimeException {
  private final String code;
  public CourierDashboardException(String code) {
    super("Courier dashboard is temporarily unavailable. Please try again later.");
    this.code = code;
  }
  public String code() { return code; }
}
