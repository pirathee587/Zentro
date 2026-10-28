package com.zentro.parcelpricing.error;

/** Pricing could not produce a fee, so the booking was not saved. */
public class PricingUnavailableException extends RuntimeException {
  public PricingUnavailableException(Throwable cause) {
    super("Pricing is temporarily unavailable. The booking was not created; please try again.", cause);
  }
}
