package com.zentro.parcelpricing.exception;

public class DuplicateExternalReferenceException extends RuntimeException {
  public DuplicateExternalReferenceException() {
    super("The external order reference has already been used with different order details.");
  }

  public DuplicateExternalReferenceException(String message) {
    super(message);
  }
}
