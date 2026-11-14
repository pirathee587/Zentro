package com.zentro.trackingreporting.reporting;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReportController.class)
public class ReportExceptionHandler {
  @ExceptionHandler(ReportValidationException.class)
  public ProblemDetail invalidRange(ReportValidationException exception, HttpServletRequest request) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    problem.setType(URI.create("about:blank"));
    problem.setTitle("Bad Request");
    problem.setInstance(URI.create(request.getRequestURI()));
    problem.setProperty("code", "INVALID_ARGUMENT");
    problem.setProperty("fieldErrors", Map.of(exception.field(), exception.getMessage()));
    return problem;
  }
}
