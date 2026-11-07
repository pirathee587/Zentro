package com.zentro.identitypartner.user.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A request value breaks a user-management rule (400). {@code field} names the request field and {@code code} the
 * rule (for example VALIDATION_FAILED, PROTECTED_FIELD, SELF_STATUS_CHANGE). The message names the rule and never
 * repeats the submitted value, so names and phone numbers are not echoed back or logged.
 */
public class InvalidUserInputException extends ResponseStatusException {
  private final String field;
  private final String code;

  public InvalidUserInputException(String field, String message) {
    this(field, "VALIDATION_FAILED", message);
  }

  public InvalidUserInputException(String field, String code, String message) {
    super(HttpStatus.BAD_REQUEST, message);
    this.field = field;
    this.code = code;
  }

  public String field() {
    return field;
  }

  public String code() {
    return code;
  }

  /** The rule text only (without the "400 BAD_REQUEST" prefix of ResponseStatusException). */
  @Override
  public String getMessage() {
    return getReason();
  }
}
