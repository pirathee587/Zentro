package com.zentro.trackingreporting.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalTrackingExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalTrackingExceptionHandler.class);

  @ExceptionHandler(TrackingNotFoundException.class)
  public ProblemDetail handleTrackingNotFound(TrackingNotFoundException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.NOT_FOUND,
        "No tracking record found for reference " + ex.getTrackingRef()
    );
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Tracking reference not found");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "TRACKING_NOT_FOUND");
    problem.setProperty("trackingRef", ex.getTrackingRef());
    return problem;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
    Map<String, String> fieldErrors = new HashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.put(error.getField(), error.getDefaultMessage());
    }
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "Validation failed for request parameters"
    );
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Validation failed");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "VALIDATION_FAILED");
    problem.setProperty("fieldErrors", fieldErrors);
    return problem;
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        ex.getMessage()
    );
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Bad Request");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "INVALID_ARGUMENT");
    return problem;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ProblemDetail handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        ex.getStatusCode(),
        ex.getReason() != null ? ex.getReason() : ex.getMessage()
    );
    problem.setType(URI.create("about:blank"));
    problem.setTitle(HttpStatus.resolve(ex.getStatusCode().value()) != null
        ? HttpStatus.resolve(ex.getStatusCode().value()).getReasonPhrase()
        : "Error");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "REQUEST_ERROR");
    return problem;
  }

  @ExceptionHandler(AccessDeniedException.class)
  public void handleAccessDenied(AccessDeniedException ex) {
    // Re-throw so Spring Security / ProblemDetailsSecurityHandlers handle 403 Forbidden properly
    throw ex;
  }

  @ExceptionHandler(Exception.class)
  public ProblemDetail handleGeneric(Exception ex, HttpServletRequest request) {
    log.error("Tracking request failed: category=unexpected_error");
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "An unexpected error occurred while retrieving tracking information."
    );
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Internal Server Error");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "INTERNAL_ERROR");
    return problem;
  }
}

