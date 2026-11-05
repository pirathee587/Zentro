package com.zentro.parcelpricing.exception;

import java.util.Map;

/**
 * A status update field that is invalid only in combination with other values,
 * for example a missing reason when the new status is FAILED. Mapped to 422 with fieldErrors.
 */
public class StatusFieldException extends RuntimeException {
  private final Map<String, String> fieldErrors;

  public StatusFieldException(String field, String message) {
    super(message);
    this.fieldErrors = Map.of(field, message);
  }

  public Map<String, String> fieldErrors() {
    return fieldErrors;
  }
}
