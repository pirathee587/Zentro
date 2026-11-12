package com.zentro.trackingreporting.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(OutputCaptureExtension.class)
class GlobalTrackingExceptionHandlerSafeLoggingTest {
  @Test
  void paginationConversionDoesNotLogRejectedValueOrException(CapturedOutput output) {
    String secret = "private-token-person@example.test";
    var request = new MockHttpServletRequest("GET", "/api/v1/tracking/mine");
    var failure = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
        secret, Integer.class, "size", null, new NumberFormatException(secret));
    ProblemDetail problem = new GlobalTrackingExceptionHandler().handleParameterConversion(failure, request);
    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).doesNotContain(secret, "Exception");
    assertThat(output.getAll()).doesNotContain(secret, "NumberFormatException", "unexpected_error");
  }

  @Test
  void genericFailureDoesNotLogUriOrExceptionChain(CapturedOutput output) {
    String secret = "private-token-person@example.test";
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tracking/" + secret);
    Exception failure = new IllegalStateException("password=" + secret,
        new RuntimeException("nested=" + secret));

    ProblemDetail problem = new GlobalTrackingExceptionHandler().handleGeneric(failure, request);

    assertThat(output.getAll()).contains("category=unexpected_error")
        .doesNotContain(secret, "IllegalStateException", "password=", "nested=");
    assertThat(problem.getStatus()).isEqualTo(500);
    assertThat(problem.getDetail()).isEqualTo("An unexpected error occurred while retrieving tracking information.");
    assertThat(problem.getProperties()).containsEntry("code", "INTERNAL_ERROR");
    assertThat(problem.getInstance().toString()).isEqualTo(request.getRequestURI());
  }
}
