package com.zentro.identitypartner.user.exception;

import com.zentro.identitypartner.courier.controller.AdminCourierController;
import com.zentro.identitypartner.courier.controller.CourierSelfProfileController;
import com.zentro.identitypartner.merchant.controller.AdminMerchantApplicationController;
import com.zentro.identitypartner.merchant.controller.MerchantApiKeyController;
import com.zentro.identitypartner.merchant.controller.MerchantApplicationController;
import com.zentro.identitypartner.user.controller.AdminUserController;
import com.zentro.identitypartner.user.controller.UserProfileController;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Standard RFC 7807 problem details error responses for Identity Partner Service APIs.
 * Includes error {@code code} and, for validation errors, {@code fieldErrors} keyed by request field.
 * Messages name the rule only: never submitted names or phone numbers, user ids, Keycloak subjects,
 * SQL or stack traces. 401 and 403 keep coming from the security starter.
 */
@RestControllerAdvice(assignableTypes = {
    AdminUserController.class,
    UserProfileController.class,
    MerchantApplicationController.class,
    AdminMerchantApplicationController.class,
    MerchantApiKeyController.class,
    AdminCourierController.class,
    CourierSelfProfileController.class
})
public class AdminUserExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(AdminUserExceptionHandler.class);

  @ExceptionHandler(InvalidUserInputException.class)
  ResponseEntity<ProblemDetail> invalidInput(InvalidUserInputException ex) {
    return validation(ex.code(), ex.getMessage(), Map.of(ex.field(), ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    ex.getBindingResult().getFieldErrors()
        .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
    return validation("VALIDATION_FAILED", "One or more fields are invalid.", fieldErrors);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
    for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
      if (cause instanceof InvalidUserInputException invalid) {
        return invalidInput(invalid);
      }
    }
    return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is missing or is not valid JSON.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ProblemDetail> badParameter(MethodArgumentTypeMismatchException ex) {
    if ("userId".equals(ex.getName())) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_USER_ID", "The user id must be a UUID.");
    }
    if ("applicationId".equals(ex.getName())) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_APPLICATION_ID", "The application id must be a UUID.");
    }
    if ("courierId".equals(ex.getName())) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_COURIER_ID", "The courier id must be a UUID.");
    }
    return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "A request parameter has an invalid value.");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException ex) {
    return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Send the request body as application/json.");
  }

  @ExceptionHandler(UserNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(UserNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", UserNotFoundException.MESSAGE);
  }

  /** Other status exceptions thrown by the services; their reasons are fixed rule texts. */
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetail> status(ResponseStatusException ex) {
    HttpStatusCode code = ex.getStatusCode();
    if (ex instanceof UserNotFoundException) {
      return problem(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", UserNotFoundException.MESSAGE);
    }
    if (code.value() == HttpStatus.NOT_FOUND.value()) {
      String reason = ex.getReason();
      if (reason == null || "User not found".equalsIgnoreCase(reason.trim()) || "User does not exist".equalsIgnoreCase(reason.trim())) {
        return problem(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", UserNotFoundException.MESSAGE);
      }
      return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", reason);
    }
    String detail = ex.getReason() == null ? "The request could not be completed." : ex.getReason();
    String errorCode;
    if (code.value() == HttpStatus.BAD_REQUEST.value()) {
      errorCode = "VALIDATION_FAILED";
    } else if (code.value() == HttpStatus.CONFLICT.value()) {
      errorCode = "CONFLICT";
    } else if (code.is4xxClientError()) {
      errorCode = "REQUEST_REJECTED";
    } else {
      errorCode = "SERVICE_UNAVAILABLE";
    }
    return problem(code, errorCode, detail);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ProblemDetail> database(DataAccessException ex) {
    log.warn("Database request failed on the database: {}", ex.getClass().getSimpleName());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "USERS_UNAVAILABLE",
        "User data is temporarily unavailable. Please try again.");
  }

  private static ResponseEntity<ProblemDetail> validation(String code, String detail, Map<String, String> fieldErrors) {
    ResponseEntity<ProblemDetail> response = problem(HttpStatus.BAD_REQUEST, code, detail);
    response.getBody().setProperty("fieldErrors", fieldErrors);
    return response;
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String code, String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    HttpStatus known = HttpStatus.resolve(status.value());
    body.setTitle(known == null ? "Error" : known.getReasonPhrase());
    body.setProperty("code", code);
    return ResponseEntity.status(status).body(body);
  }
}
