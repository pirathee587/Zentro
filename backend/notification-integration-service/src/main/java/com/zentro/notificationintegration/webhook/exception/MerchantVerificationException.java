package com.zentro.notificationintegration.webhook.exception;

import org.springframework.http.HttpStatus;

/**
 * The caller could not be confirmed as an approved, active merchant: 401 (no usable identity), 403 (not approved
 * or not active) or 503 (Identity service unavailable).
 */
public class MerchantVerificationException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public MerchantVerificationException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
