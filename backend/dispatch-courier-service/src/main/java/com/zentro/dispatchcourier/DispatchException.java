package com.zentro.dispatchcourier;

import org.springframework.http.HttpStatus;

public class DispatchException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public DispatchException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() { return status; }
  public String code() { return code; }

  public static DispatchException assigned() {
    return new DispatchException(HttpStatus.CONFLICT, "DELIVERY_ALREADY_ASSIGNED",
        "This delivery has already been assigned. The queue has changed; refresh and choose another delivery.");
  }
}
