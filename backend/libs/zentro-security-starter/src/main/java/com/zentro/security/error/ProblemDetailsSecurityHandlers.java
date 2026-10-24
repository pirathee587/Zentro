package com.zentro.security.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;

/** Produces non-sensitive RFC 7807 responses for authentication and authorization failures. */
public final class ProblemDetailsSecurityHandlers {
  private ProblemDetailsSecurityHandlers() { }
  public static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper mapper) {
    return (request, response, exception) -> write(mapper, response, HttpStatus.UNAUTHORIZED, "Authentication required", request);
  }
  public static AccessDeniedHandler accessDeniedHandler(ObjectMapper mapper) {
    return (request, response, exception) -> write(mapper, response, HttpStatus.FORBIDDEN, "Access denied", request);
  }
  private static void write(ObjectMapper mapper, HttpServletResponse response, HttpStatus status, String detail, HttpServletRequest request) throws IOException {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    problem.setInstance(java.net.URI.create(request.getRequestURI()));
    response.setStatus(status.value()); response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), problem);
  }
}
