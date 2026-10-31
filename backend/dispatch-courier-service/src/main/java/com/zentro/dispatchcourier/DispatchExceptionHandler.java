package com.zentro.dispatchcourier;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = DispatchQueueController.class)
public class DispatchExceptionHandler {
  @ExceptionHandler(DispatchException.class)
  ResponseEntity<ProblemDetail> dispatch(DispatchException error) {
    return problem(error.status(), error.code(), error.getMessage());
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
  ResponseEntity<ProblemDetail> invalid(Exception error) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Check the required UUIDs, dates and request fields.");
  }

  @ExceptionHandler({DataAccessException.class, org.springframework.transaction.TransactionException.class})
  ResponseEntity<ProblemDetail> database(Exception error) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "DISPATCH_STORAGE_UNAVAILABLE", "Assignment data is unavailable. Refresh before trying again.");
  }

  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetail> dependency(ResponseStatusException error) {
    return problem(HttpStatus.valueOf(error.getStatusCode().value()), "DISPATCH_REQUEST_FAILED", error.getReason());
  }

  private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String message) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, message);
    body.setTitle(status.getReasonPhrase());
    body.setProperty("code", code);
    return ResponseEntity.status(status).body(body);
  }
}
