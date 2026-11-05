package com.zentro.parcelpricing.exception;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalPricingExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalPricingExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail handleValidationException(MethodArgumentNotValidException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed for request.");
    problem.setTitle("Bad Request");
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      errors.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    problem.setProperty("fieldErrors", errors);
    problem.setProperty("invalidFields", errors);
    return problem;
  }

  @ExceptionHandler(PricingValidationException.class)
  public ProblemDetail handlePricingValidationException(PricingValidationException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setTitle("Bad Request");
    problem.setProperty("fieldErrors", ex.fieldErrors());
    problem.setProperty("invalidFields", ex.fieldErrors());
    return problem;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
    if (ex.getCause() instanceof MismatchedInputException mix) {
      List<JsonMappingException.Reference> path = mix.getPath();
      if (path != null && !path.isEmpty()) {
        String fieldName = path.get(path.size() - 1).getFieldName();
        if (fieldName != null && !fieldName.isBlank()) {
          String message;
          if (mix.getTargetType() == ParcelSize.class) {
            message = "Unsupported parcelSize value. Allowed values: SMALL, MEDIUM, LARGE.";
          } else if (mix.getTargetType() == DestinationType.class) {
            message = "Unsupported destinationType value. Allowed values: SAME_CITY, OTHER_DISTRICT.";
          } else {
            message = "Invalid value for field '" + fieldName + "'.";
          }
          Map<String, String> errors = Map.of(fieldName, message);
          ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message);
          problem.setTitle("Bad Request");
          problem.setProperty("fieldErrors", errors);
          problem.setProperty("invalidFields", errors);
          return problem;
        }
      }
    }
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "The request body is not valid JSON or contains an unsupported value.");
    problem.setTitle("Bad Request");
    return problem;
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail handleIllegalArgumentException(IllegalArgumentException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    problem.setTitle("Bad Request");
    return problem;
  }

  @ExceptionHandler(PricingUnavailableException.class)
  public ProblemDetail handlePricingUnavailable(PricingUnavailableException ex) {
    log.warn("Pricing quotation unavailable: {}",
        ex.getCause() == null ? ex.getClass().getSimpleName() : ex.getCause().getClass().getSimpleName());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
    problem.setTitle("Service Unavailable");
    return problem;
  }

  @ExceptionHandler(DataAccessException.class)
  public ProblemDetail handleDataAccess(DataAccessException ex) {
    log.error("Pricing database access failed: {}", ex.getClass().getSimpleName());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Pricing data is temporarily unavailable. Please try again later.");
    problem.setTitle("Service Unavailable");
    return problem;
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ProblemDetail handleResponseStatusException(ResponseStatusException ex) {
    HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getReason());
    problem.setTitle(status.getReasonPhrase());
    return problem;
  }
}
