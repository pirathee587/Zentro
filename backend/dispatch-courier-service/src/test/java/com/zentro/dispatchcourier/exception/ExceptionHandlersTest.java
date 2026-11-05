package com.zentro.dispatchcourier.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExceptionHandlersTest {

  private final DispatchExceptionHandler dispatchHandler = new DispatchExceptionHandler();
  private final ProofExceptionHandler proofHandler = new ProofExceptionHandler();

  @Test
  void dispatchExceptionHandlerMapsDomainAndInfrastructureErrorsToProblemDetail() {
    var assigned = dispatchHandler.dispatch(DispatchException.assigned());
    assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(assigned.getBody()).isNotNull();
    assertThat(assigned.getBody().getProperties()).containsEntry("code", "DELIVERY_ALREADY_ASSIGNED");

    var dataAccess = dispatchHandler.database(new DataAccessResourceFailureException("db down"));
    assertThat(dataAccess.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(dataAccess.getBody()).isNotNull();
    assertThat(dataAccess.getBody().getProperties()).containsEntry("code", "DISPATCH_STORAGE_UNAVAILABLE");

    var upstream = dispatchHandler.dependency(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
    assertThat(upstream.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(upstream.getBody()).isNotNull();
    assertThat(upstream.getBody().getProperties()).containsEntry("code", "DISPATCH_REQUEST_FAILED");
  }

  @Test
  void proofExceptionHandlerIncludesValidationFieldErrorsAndMapsDependencyErrors() {
    var validation = new ProofException(400, "INVALID_PROOF", "Check the proof fields.",
        Map.of("recipientName", "This field is required."));
    var response = proofHandler.proof(validation);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().getProperties())
        .containsEntry("code", "INVALID_PROOF")
        .containsEntry("fieldErrors", Map.of("recipientName", "This field is required."));

    var status = proofHandler.identity(new ResponseStatusException(HttpStatus.FORBIDDEN, "Inactive courier"));
    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(status.getBody()).isNotNull();
    assertThat(status.getBody().getProperties()).containsEntry("code", "COURIER_VERIFICATION_FAILED");
  }
}
