package com.zentro.parcelpricing.exception;

import java.util.Map;

/**
 * Thrown when a price quotation request fails domain or cross-field validation.
 * Carries field-level error messages so clients can map errors to inputs.
 */
public class PricingValidationException extends RuntimeException {
  private final Map<String, String> fieldErrors;

  public PricingValidationException(String message, Map<String, String> fieldErrors) {
    super(message);
    this.fieldErrors = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(fieldErrors));
  }

  public Map<String, String> fieldErrors() {
    return fieldErrors;
  }
}

