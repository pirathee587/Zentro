package com.zentro.identitypartner.courier.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** Machine codes distinguish caller/profile failures from an absent or unavailable upstream route. */
@RestControllerAdvice(assignableTypes = CourierIdentityReadController.class)
public class CourierIdentityReadExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> request(ResponseStatusException failure) {
    int status = failure.getStatusCode().value();
    String code = switch (status) {
      case 401 -> "COURIER_UNAUTHENTICATED";
      case 403 -> "COURIER_ACCESS_DENIED";
      case 404 -> "COURIER_PROFILE_NOT_FOUND";
      default -> "COURIER_IDENTITY_UNAVAILABLE";
    };
    return problem(status, code);
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  public ResponseEntity<ProblemDetail> storage(Exception ignored) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE.value(), "COURIER_IDENTITY_UNAVAILABLE");
  }

  private static ResponseEntity<ProblemDetail> problem(int status, String code) {
    var body = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(status),
        "Courier identity could not be verified.");
    body.setProperty("code", code);
    return ResponseEntity.status(status).header("Cache-Control", "no-store").body(body);
  }
}
