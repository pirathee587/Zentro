package com.zentro.parcelpricing.error;

/** The Idempotency-Key was already used by this customer for a different booking request. */
public class IdempotencyConflictException extends RuntimeException {
  public IdempotencyConflictException() {
    super("This Idempotency-Key was already used for a different booking request.");
  }
}
