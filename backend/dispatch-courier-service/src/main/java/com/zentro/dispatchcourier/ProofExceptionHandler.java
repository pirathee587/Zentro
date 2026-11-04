package com.zentro.dispatchcourier;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = CourierDeliveryController.class)
public class ProofExceptionHandler {
  @ExceptionHandler(ProofException.class)
  ResponseEntity<ProblemDetail> proof(ProofException error) {
    var body = ProblemDetail.forStatusAndDetail(org.springframework.http.HttpStatusCode.valueOf(error.status()), error.getMessage());
    body.setProperty("code", error.code());
    if (!error.fieldErrors().isEmpty()) body.setProperty("fieldErrors", error.fieldErrors());
    return ResponseEntity.status(error.status()).header("Cache-Control", "no-store").body(body);
  }
  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
  ResponseEntity<ProblemDetail> invalid(Exception ignored) {
    return proof(new ProofException(400, "INVALID_REQUEST", "Check the delivery UUID and proof fields."));
  }
  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ResponseEntity<ProblemDetail> storage(Exception ignored) {
    return proof(new ProofException(503, "PROOF_STORAGE_UNAVAILABLE", "Proof storage is unavailable. Refresh before retrying."));
  }
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ProblemDetail> identity(ResponseStatusException error) {
    int status = error.getStatusCode().value();
    return proof(new ProofException(status, "COURIER_VERIFICATION_FAILED",
        status == 503 ? "Courier identity is unavailable. Try again later." : "Courier access could not be verified."));
  }
}
