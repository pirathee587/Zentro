package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * POST /api/v1/parcels through the application's real security filter chain: a bearer token is sent in the
 * Authorization header and goes through the resource-server filter and the Keycloak realm-role converter.
 * Only the signature check (JwtDecoder) is replaced, so tokens with chosen roles can be used.
 * Never touches the shared database. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class BookingSecurityIntegrationTest {
  private static final String BODY = """
      {
        "sender": {"name": "Secure Sender", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Secure Recipient", "phone": "0712345678", "address": "45 Temple Street, Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Small Box", "size": "SMALL"}
      }
      """;

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("zentro.security.issuer-uri", () -> "http://localhost:8180/realms/zentro");
    registry.add("zentro.security.audience", () -> "zentro-api");
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @MockBean JwtDecoder jwtDecoder;

  @BeforeEach
  void rejectUnknownTokens() {
    when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("invalid token"));
  }

  /** Registers a bearer token that decodes to this subject with these Keycloak realm roles. */
  private String token(String subject, String... realmRoles) {
    String value = "token-" + subject;
    Jwt jwt = Jwt.withTokenValue(value)
        .header("alg", "RS256")
        .subject(subject)
        .issuer("http://localhost:8180/realms/zentro")
        .audience(List.of("zentro-api"))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .claim("realm_access", Map.of("roles", List.of(realmRoles)))
        .build();
    doReturn(jwt).when(jwtDecoder).decode(value); // doReturn: does not call the throwing default stub
    return value;
  }

  private MockHttpServletResponse book(String authorization) throws Exception {
    var request = post("/api/v1/parcels").contentType(MediaType.APPLICATION_JSON).content(BODY);
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    return mvc.perform(request).andReturn().getResponse();
  }

  private int parcels(String subject) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel WHERE customer_subject = ?", Integer.class, subject);
  }

  @Test
  @DisplayName("A customer bearer token books the parcel (201) for the token subject")
  void customerTokenBooks() throws Exception {
    MockHttpServletResponse response = book("Bearer " + token("sec-customer", "CUSTOMER", "offline_access"));

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(parcels("sec-customer")).isEqualTo(1);
  }

  @Test
  @DisplayName("Realm roles are matched regardless of case (customer -> ROLE_CUSTOMER)")
  void lowercaseRealmRoleIsAccepted() throws Exception {
    assertThat(book("Bearer " + token("sec-lowercase", "customer")).getStatus()).isEqualTo(201);
  }

  @Test
  @DisplayName("No Authorization header returns 401 as problem+json and stores nothing")
  void missingTokenIsUnauthorized() throws Exception {
    long before = jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel", Long.class);

    MockHttpServletResponse response = book(null);

    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parcel.parcel", Long.class)).isEqualTo(before);
  }

  @ParameterizedTest(name = "\"{0}\" -> 401")
  @ValueSource(strings = {"Bearer not-a-valid-token", "Bearer ", "Basic dXNlcjpwYXNz"})
  @DisplayName("An invalid, empty or non-bearer Authorization header returns 401")
  void invalidTokenIsUnauthorized(String authorization) throws Exception {
    assertThat(book(authorization).getStatus()).isEqualTo(401);
  }

  @ParameterizedTest(name = "{0} -> 403")
  @ValueSource(strings = {"MERCHANT", "COURIER", "DISPATCHER", "SERVICE"})
  @DisplayName("Valid tokens without the CUSTOMER role return 403 and store nothing")
  void otherRolesAreForbidden(String role) throws Exception {
    String subject = "sec-" + role.toLowerCase();

    MockHttpServletResponse response = book("Bearer " + token(subject, role));

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentType()).startsWith("application/problem+json");
    assertThat(parcels(subject)).isZero();
  }

  @Test
  @DisplayName("A token with no realm roles returns 403")
  void noRolesIsForbidden() throws Exception {
    assertThat(book("Bearer " + token("sec-none")).getStatus()).isEqualTo(403);
  }

  @Test
  @DisplayName("A user who has CUSTOMER plus another role (default role in the realm) can book")
  void customerWithAnotherRoleCanBook() throws Exception {
    assertThat(book("Bearer " + token("sec-courier-customer", "COURIER", "CUSTOMER")).getStatus()).isEqualTo(201);
  }
}
