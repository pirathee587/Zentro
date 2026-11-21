package com.zentro.parcelpricing.assignment;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

@RestControllerAdvice(assignableTypes=ParcelClaimController.class)
public class CoordinationExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> request(ResponseStatusException failure) {
    return response(failure.getStatusCode().value());
  }
  @ExceptionHandler( {
    DataAccessException.class,TransactionException.class
  })
  public ResponseEntity<ProblemDetail> storage(Exception failure) {
    return response(503);
  }
  private ResponseEntity<ProblemDetail> response(int status) {
    var body=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),"Parcel assignment coordination could not be completed.");
    body.setProperty("code",status==409?"ASSIGNMENT_COORDINATION_CONFLICT":"ASSIGNMENT_COORDINATION_UNAVAILABLE");
    return ResponseEntity.status(status).header("Cache-Control","no-store").body(body);
  }
}
