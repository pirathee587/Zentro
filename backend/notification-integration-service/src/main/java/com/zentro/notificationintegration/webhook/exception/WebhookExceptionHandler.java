package com.zentro.notificationintegration.webhook.exception;

import com.zentro.notificationintegration.webhook.controller.MerchantWebhookController;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Errors of the merchant webhook API as {@code application/problem+json} with a {@code code} property. Messages
 * name the rule only: never the secret, the token, a stack trace or internal details.
 */
@RestControllerAdvice(assignableTypes = MerchantWebhookController.class)
public class WebhookExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(WebhookExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ProblemDetail> invalidBody(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    ex.getBindingResult().getFieldErrors()
        .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
    return validationFailed(fieldErrors);
  }

  @ExceptionHandler(InvalidWebhookUrlException.class)
  ResponseEntity<ProblemDetail> invalidUrl(InvalidWebhookUrlException ex) {
    return validationFailed(Map.of("url", ex.getMessage()));
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  ResponseEntity<ProblemDetail> malformed(Exception ex) {
    return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
        "The request could not be read.");
  }

  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ProblemDetail> unsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
    return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type",
        "Send the request body as application/json.");
  }

  @ExceptionHandler(WebhookNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(WebhookNotFoundException ex) {
    return problem(HttpStatus.NOT_FOUND, "WEBHOOK_NOT_FOUND", "Webhook not found", ex.getMessage());
  }

  @ExceptionHandler(MerchantVerificationException.class)
  ResponseEntity<ProblemDetail> merchant(MerchantVerificationException ex) {
    return problem(ex.status(), ex.code(), ex.status().getReasonPhrase(), ex.getMessage());
  }

  @ExceptionHandler(WebhooksUnavailableException.class)
  ResponseEntity<ProblemDetail> unavailable(WebhooksUnavailableException ex) {
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOKS_UNAVAILABLE", "Webhooks unavailable",
        ex.getMessage());
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ProblemDetail> database(DataAccessException ex) {
    log.warn("Webhook request failed on the database: {}", ex.getClass().getSimpleName());
    return problem(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOKS_UNAVAILABLE", "Webhooks unavailable",
        "Webhook settings are temporarily unavailable. Please try again.");
  }

  private static ResponseEntity<ProblemDetail> validationFailed(Map<String, String> fieldErrors) {
    ResponseEntity<ProblemDetail> response = problem(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION_FAILED",
        "Validation failed", "One or more fields are invalid.");
    response.getBody().setProperty("fieldErrors", fieldErrors);
    return response;
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String title,
      String detail) {
    ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
    body.setTitle(title);
    body.setProperty("code", code);
    return ResponseEntity.status(status).body(body);
  }
}
