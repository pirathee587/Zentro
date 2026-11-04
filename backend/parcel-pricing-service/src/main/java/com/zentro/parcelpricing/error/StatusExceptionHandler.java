package com.zentro.parcelpricing.error;

import com.zentro.parcelpricing.controller.ParcelStatusController;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Problem responses for the parcel status endpoint only. Every body has a "code" property.
 * Messages contain status names and ids only, never notes, names, phone numbers or addresses.
 */
@RestControllerAdvice(assignableTypes = ParcelStatusController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StatusExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(StatusExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    return validationProblem(fieldErrors);
  }

  @ExceptionHandler(StatusFieldException.class)
  public ProblemDetail handleStatusField(StatusFieldException ex) {
    return validationProblem(ex.fieldErrors());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
    return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
        "The request body is not valid JSON or contains an unknown status.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
    return problem(HttpStatus.BAD_REQUEST, "INVALID_PARCEL_ID", "Invalid parcel id",
        "The parcel id must be a UUID.");
  }

  @ExceptionHandler(ParcelNotFoundException.class)
  public ProblemDetail handleNotFound(ParcelNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "PARCEL_NOT_FOUND", "Parcel not found", ex.getMessage());
  }

  @ExceptionHandler(InvalidTransitionException.class)
  public ProblemDetail handleInvalidTransition(InvalidTransitionException ex) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, ex.code().name(),
        ex.code() == InvalidTransitionException.Code.PARCEL_TERMINAL ? "Parcel status is final" : "Invalid status transition",
        ex.getMessage());
    problem.setProperty("currentStatus", ex.currentStatus().name());
    problem.setProperty("requestedStatus", ex.requestedStatus().name());
    problem.setProperty("allowedNext", ex.allowedNext().stream().map(Enum::name).toList());
    return problem;
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ProblemDetail handleConcurrentUpdate(OptimisticLockingFailureException ex) {
    return problem(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "Concurrent update",
        "The parcel changed while this update was running. Read it again and retry.");
  }

  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail handleDataAccess(DataAccessException ex) {
    log.error("Parcel status update could not be stored: {}", ex.getClass().getSimpleName());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "STATUS_UPDATE_UNAVAILABLE", "Status update unavailable",
        "The status change could not be saved right now. Nothing was changed; please try again.");
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    return problem(status, status.name(), status.getReasonPhrase(), ex.getReason());
  }

  private static ProblemDetail validationProblem(Map<String, String> fieldErrors) {
    ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED", "Validation failed",
        "Some status update fields are missing or invalid.");
    problem.setProperty("fieldErrors", fieldErrors);
    return problem;
  }

  private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code);
    return problem;
  }
}
