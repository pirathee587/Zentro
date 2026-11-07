package com.zentro.identitypartner.user.exception;

/**
 * A request value breaks a user-management rule (400). {@code field} names the request field; the message names the
 * rule and never repeats the submitted value, so names and phone numbers are not echoed back or logged.
 */
public class InvalidUserInputException extends RuntimeException {
  private final String field;
  private final String code;

  public InvalidUserInputException(String field, String message) {
    this(field, "VALIDATION_FAILED", message);
  }

  public InvalidUserInputException(String field, String code, String message) {
    super(message);
    this.field = field;
    this.code = code;
  }

  public String field() {
    return field;
  }

  public String code() {
    return code;
  }
}
