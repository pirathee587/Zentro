package com.zentro.dispatchcourier;

import java.util.Map;

public class ProofException extends RuntimeException {
  private final int status;
  private final String code;
  private final Map<String, String> fieldErrors;
  public ProofException(int status, String code, String message) { this(status, code, message, Map.of()); }
  public ProofException(int status, String code, String message, Map<String, String> errors) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = Map.copyOf(errors);
  }
  public int status() { return status; }
  public String code() { return code; }
  public Map<String, String> fieldErrors() { return fieldErrors; }
}
