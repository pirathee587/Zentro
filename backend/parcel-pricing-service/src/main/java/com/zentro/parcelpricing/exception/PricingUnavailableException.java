package com.zentro.parcelpricing.exception;

/** Pricing could not produce a fee, so the operation could not complete. */
public class PricingUnavailableException extends RuntimeException {
  public PricingUnavailableException(Throwable cause) {
    super("Pricing is temporarily unavailable. The booking was not created; please try again.", cause);
  }

  public PricingUnavailableException(String message) {
    super(message);
  }

  public PricingUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
