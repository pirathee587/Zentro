package com.zentro.security.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

class ProblemDetailsSecurityHandlersTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  @DisplayName("AuthenticationEntryPoint writes RFC 7807 problem details with 401 status")
  void authenticationEntryPointWritesProblemDetails() throws Exception {
    var entryPoint = ProblemDetailsSecurityHandlers.authenticationEntryPoint(mapper);
    var request = new MockHttpServletRequest("GET", "/api/v1/protected");
    var response = new MockHttpServletResponse();

    entryPoint.commence(request, response, new BadCredentialsException("Invalid token"));

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");

    JsonNode body = mapper.readTree(response.getContentAsString());
    assertThat(body.get("status").asInt()).isEqualTo(401);
    assertThat(body.get("title").asText()).isEqualTo("Unauthorized");
    assertThat(body.get("detail").asText()).isEqualTo("Authentication required");
    assertThat(body.get("instance").asText()).isEqualTo("/api/v1/protected");
  }

  @Test
  @DisplayName("AccessDeniedHandler writes RFC 7807 problem details with 403 status")
  void accessDeniedHandlerWritesProblemDetails() throws Exception {
    var accessDeniedHandler = ProblemDetailsSecurityHandlers.accessDeniedHandler(mapper);
    var request = new MockHttpServletRequest("POST", "/api/v1/admin/merchants");
    var response = new MockHttpServletResponse();

    accessDeniedHandler.handle(request, response, new AccessDeniedException("Access denied"));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).isEqualTo("application/problem+json");

    JsonNode body = mapper.readTree(response.getContentAsString());
    assertThat(body.get("status").asInt()).isEqualTo(403);
    assertThat(body.get("title").asText()).isEqualTo("Forbidden");
    assertThat(body.get("detail").asText()).isEqualTo("Access denied");
    assertThat(body.get("instance").asText()).isEqualTo("/api/v1/admin/merchants");
  }
}
