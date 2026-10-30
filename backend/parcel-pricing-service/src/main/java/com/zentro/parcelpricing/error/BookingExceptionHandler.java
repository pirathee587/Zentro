package com.zentro.parcelpricing.error;

import com.zentro.parcelpricing.controller.ParcelBookingController;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Error responses for the booking endpoint only. Messages never include names, phone numbers or addresses,
 * and logs record only the exception type.
 */
@RestControllerAdvice(assignableTypes = ParcelBookingController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BookingExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(BookingExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
        "Some booking details are missing or invalid.");
    problem.setProperty("fieldErrors", fieldErrors);
    return problem;
  }

  /** Pricing rejected the parcel data (for example an unsupported combination). */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handlePricingRejected(IllegalArgumentException ex) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Booking cannot be priced",
        "The parcel details could not be priced: " + ex.getMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
    return problem(HttpStatus.BAD_REQUEST, "Malformed request",
        "The request body is not valid JSON or contains an unsupported value.");
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  public ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
    return problem(HttpStatus.CONFLICT, "Idempotency conflict", ex.getMessage());
  }

  @ExceptionHandler(PricingUnavailableException.class)
  public ProblemDetail handlePricingUnavailable(PricingUnavailableException ex) {
    log.warn("Booking rejected because pricing failed: {}", ex.getCause() == null
        ? "unknown" : ex.getCause().getClass().getSimpleName());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Pricing unavailable", ex.getMessage());
  }

  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail handleDataAccess(DataAccessException ex) {
    log.error("Booking could not be stored: {}", ex.getClass().getSimpleName());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "Booking unavailable",
        "The booking could not be saved right now. Please try again.");
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ProblemDetail handleResponseStatus(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    return problem(status, status.getReasonPhrase(), ex.getReason());
  }

  private static ProblemDetail problem(HttpStatus status, String title, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    return problem;
  }
}
