package com.zentro.identitypartner.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.identitypartner.user.dto.AdminUpdateUserRequest;
import com.zentro.identitypartner.user.dto.UserProfile;
import com.zentro.identitypartner.user.exception.InvalidUserInputException;
import com.zentro.identitypartner.user.exception.UserNotFoundException;
import com.zentro.identitypartner.user.service.UserProfileService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * ZD-042 hardening of {@code /api/v1/admin/users} through the real security chain (bearer token -> resource server
 * -> Keycloak realm-role converter); only the signature check and the service are replaced.
 */
@WebMvcTest(controllers = AdminUserController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class AdminUserApiHardeningTest {
  private static final String PATH = "/api/v1/admin/users";
  private static final UUID TARGET = UUID.randomUUID();
  private static final String DISPATCHER_SUBJECT = "kc-subject-7f3a9c";

  @Autowired private MockMvc mvc;
  @MockBean private UserProfileService profiles;
  @MockBean private JwtDecoder jwtDecoder;

  @BeforeEach
  void rejectUnknownTokens() {
    when(jwtDecoder.decode(anyString())).thenThrow(new BadJwtException("invalid token"));
  }

  /** A bearer token that decodes to this subject with these Keycloak realm roles. */
  private String bearer(String subject, String... roles) {
    String value = "token-" + subject + "-" + String.join("-", roles);
    Jwt jwt = Jwt.withTokenValue(value).header("alg", "RS256").subject(subject)
        .issuer("http://localhost:8180/realms/zentro").audience(List.of("zentro-api"))
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
        .claim("realm_access", Map.of("roles", List.of(roles))).build();
    doReturn(jwt).when(jwtDecoder).decode(value);
    return "Bearer " + value;
  }

  private String dispatcher() {
    return bearer(DISPATCHER_SUBJECT, "DISPATCHER");
  }

  private MvcResult patchUser(String authorization, String body) throws Exception {
    var builder = patch(PATH + "/" + TARGET).contentType("application/json").content(body);
    if (authorization != null) {
      builder.header("Authorization", authorization);
    }
    return mvc.perform(builder).andReturn();
  }

  private static UserProfile user(String status) {
    return new UserProfile(TARGET, "kamal@zentro.test", "Kamal", "0772223333", "ACTIVE".equals(status), status, null, List.of());
  }

  // ─── Authentication and roles ───────────────────────────────────────────────

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"GET, ''", "GET, /00000000-0000-0000-0000-000000000001", "PATCH, /00000000-0000-0000-0000-000000000001"})
  @DisplayName("Without a valid token every endpoint returns 401 problem+json and the service is not called")
  void unauthenticated(String method, String suffix) throws Exception {
    mvc.perform(request(HttpMethod.valueOf(method), PATH + suffix).contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    mvc.perform(request(HttpMethod.valueOf(method), PATH + suffix).header("Authorization", "Bearer forged")
            .contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(profiles);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER", "SERVICE", "ADMIN"})
  @DisplayName("Every non-dispatcher role (including SERVICE and a fake ADMIN) gets 403 on every endpoint")
  void otherRolesForbidden(String role) throws Exception {
    String auth = bearer("user-" + role, role);

    mvc.perform(get(PATH).header("Authorization", auth)).andExpect(status().isForbidden());
    mvc.perform(get(PATH + "/" + TARGET).header("Authorization", auth)).andExpect(status().isForbidden());
    assertThat(patchUser(auth, "{\"accountStatus\":\"DISABLED\"}").getResponse().getStatus()).isEqualTo(403);
    verifyNoInteractions(profiles);
  }

  @Test
  @DisplayName("A user with CUSTOMER plus DISPATCHER (default realm role) is a dispatcher")
  void dispatcherWithDefaultCustomerRole() throws Exception {
    when(profiles.listUsers(null, null)).thenReturn(List.of());

    mvc.perform(get(PATH).header("Authorization", bearer("multi", "CUSTOMER", "DISPATCHER")))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("When the service refuses the caller (service-level check) the API answers 403")
  void serviceLevelDenial() throws Exception {
    when(profiles.adminUpdate(eq(TARGET), any(), any())).thenThrow(new AccessDeniedException("Only dispatchers can manage users."));

    assertThat(patchUser(dispatcher(), "{\"name\":\"Kamal\"}").getResponse().getStatus()).isEqualTo(403);
  }

  // ─── Protected and unknown fields ───────────────────────────────────────────

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"roles", "email", "keycloakSubject", "accountType", "enabled", "id"})
  @DisplayName("Protected fields return 400 PROTECTED_FIELD naming the field; the service is not called")
  void protectedFields(String field) throws Exception {
    mvc.perform(patch(PATH + "/" + TARGET).header("Authorization", dispatcher()).contentType("application/json")
            .content("{\"name\":\"Kamal\",\"" + field + "\":[\"DISPATCHER\"]}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("PROTECTED_FIELD"))
        .andExpect(jsonPath("$.fieldErrors." + field).value("Field '" + field + "' cannot be changed through this API."));
    verifyNoInteractions(profiles);
  }

  @Test
  @DisplayName("An unknown field returns 400 UNKNOWN_FIELD naming it")
  void unknownField() throws Exception {
    mvc.perform(patch(PATH + "/" + TARGET).header("Authorization", dispatcher()).contentType("application/json")
            .content("{\"nickname\":\"K\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
        .andExpect(jsonPath("$.fieldErrors.nickname").value("Unknown field 'nickname'."));
    verifyNoInteractions(profiles);
  }

  // ─── Validation and error bodies ────────────────────────────────────────────

  @Test
  @DisplayName("Bean validation errors come back as 400 VALIDATION_FAILED with fieldErrors, without echoing values")
  void beanValidation() throws Exception {
    String longName = "N".repeat(201);
    MvcResult result = patchUser(dispatcher(), "{\"name\":\"" + longName + "\",\"accountStatus\":\"SUPERUSER\"}");

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("\"code\":\"VALIDATION_FAILED\"", "\"name\":\"Name must not exceed 200 characters\"",
        "\"accountStatus\":\"accountStatus must be ACTIVE, DISABLED, or SUSPENDED\"");
    assertThat(body).doesNotContain(longName, "SUPERUSER");
    verifyNoInteractions(profiles);
  }

  @Test
  @DisplayName("A non-text value and malformed JSON are 400 with a clear code")
  void malformedBodies() throws Exception {
    MvcResult nonText = patchUser(dispatcher(), "{\"accountStatus\":3}");
    assertThat(nonText.getResponse().getStatus()).isEqualTo(400);
    assertThat(nonText.getResponse().getContentAsString()).contains("\"accountStatus\":\"accountStatus must be a text value.\"");

    MvcResult broken = patchUser(dispatcher(), "{\"name\":");
    assertThat(broken.getResponse().getStatus()).isEqualTo(400);
    assertThat(broken.getResponse().getContentAsString()).contains("\"code\":\"MALFORMED_REQUEST\"");
    verifyNoInteractions(profiles);
  }

  @Test
  @DisplayName("Lower-case status and a reason reach the service unchanged (the service normalises them)")
  void reasonAndLowerCaseStatusAccepted() throws Exception {
    when(profiles.adminUpdate(eq(TARGET), any(), any())).thenReturn(user("SUSPENDED"));

    MvcResult result = patchUser(dispatcher(), "{\"accountStatus\":\"suspended\",\"reason\":\"Chargeback review\"}");

    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    ArgumentCaptor<AdminUpdateUserRequest> request = ArgumentCaptor.forClass(AdminUpdateUserRequest.class);
    verify(profiles).adminUpdate(eq(TARGET), any(), request.capture());
    assertThat(request.getValue()).isEqualTo(new AdminUpdateUserRequest(null, null, "suspended", "Chargeback review"));
    assertThat(result.getResponse().getContentAsString()).contains("\"accountStatus\":\"SUSPENDED\"")
        .doesNotContain("keycloak", "subject");
  }

  @Test
  @DisplayName("A reason longer than 500 characters is 400")
  void longReason() throws Exception {
    MvcResult result = patchUser(dispatcher(), "{\"accountStatus\":\"DISABLED\",\"reason\":\"" + "r".repeat(501) + "\"}");

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    assertThat(result.getResponse().getContentAsString()).contains("\"reason\":\"Reason must not exceed 500 characters\"");
    verifyNoInteractions(profiles);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
      "EMPTY_UPDATE, request",
      "SELF_STATUS_CHANGE, accountStatus",
      "VALIDATION_FAILED, phone",
  })
  @DisplayName("Rule violations from the service are 400 problem+json with the code and the field")
  void serviceRuleViolations(String code, String field) throws Exception {
    when(profiles.adminUpdate(eq(TARGET), any(), any()))
        .thenThrow(new InvalidUserInputException(field, code, "Rule text for " + field + "."));

    mvc.perform(patch(PATH + "/" + TARGET).header("Authorization", dispatcher()).contentType("application/json")
            .content("{\"phone\":\"12345\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value(code))
        .andExpect(jsonPath("$.detail").value("Rule text for " + field + "."))
        .andExpect(jsonPath("$.fieldErrors." + field).value("Rule text for " + field + "."));
  }

  @Test
  @DisplayName("An invalid list status filter is 400 with fieldErrors.status")
  void invalidStatusFilter() throws Exception {
    when(profiles.listUsers("ARCHIVED", null))
        .thenThrow(new InvalidUserInputException("status", "Invalid status: must be ACTIVE, DISABLED or SUSPENDED."));

    mvc.perform(get(PATH).param("status", "ARCHIVED").header("Authorization", dispatcher()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors.status").value("Invalid status: must be ACTIVE, DISABLED or SUSPENDED."));
  }

  @Test
  @DisplayName("Unknown user: 404 USER_NOT_FOUND without the user id or any Keycloak subject")
  void notFoundWithoutIdentifiers() throws Exception {
    when(profiles.getById(TARGET)).thenThrow(new UserNotFoundException());

    MvcResult result = mvc.perform(get(PATH + "/" + TARGET).header("Authorization", dispatcher())).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(404);
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("\"code\":\"USER_NOT_FOUND\"", "\"detail\":\"User not found.\"");
    // Only "instance" (the request's own path, standard problem+json) contains the id the caller sent.
    assertThat(body.replace("\"instance\":\"" + PATH + "/" + TARGET + "\"", "")).doesNotContain(TARGET.toString());
    assertThat(body).doesNotContain(DISPATCHER_SUBJECT, "subject");
  }

  @Test
  @DisplayName("A malformed user id is 400 INVALID_USER_ID; only the request path (instance) shows it")
  void invalidUserId() throws Exception {
    MvcResult result = mvc.perform(get(PATH + "/not-a-uuid-xyz").header("Authorization", dispatcher())).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(400);
    String body = result.getResponse().getContentAsString();
    assertThat(body).contains("\"code\":\"INVALID_USER_ID\"", "\"detail\":\"The user id must be a UUID.\"");
    assertThat(body.replace("\"instance\":\"" + PATH + "/not-a-uuid-xyz\"", "")).doesNotContain("not-a-uuid-xyz");
    verifyNoInteractions(profiles);
  }

  @Test
  @DisplayName("A database failure is 503 without SQL, class names or stack traces")
  void databaseFailure() throws Exception {
    when(profiles.listUsers(null, null))
        .thenThrow(new DataAccessResourceFailureException("Connection refused: SELECT * FROM identity.app_user"));

    MvcResult result = mvc.perform(get(PATH).header("Authorization", dispatcher())).andReturn();

    assertThat(result.getResponse().getStatus()).isEqualTo(503);
    assertThat(result.getResponse().getContentAsString()).contains("\"code\":\"USERS_UNAVAILABLE\"")
        .doesNotContain("SELECT", "identity.app_user", "DataAccess", "Exception", "at com.");
  }

  @Test
  @DisplayName("The response shape is unchanged and never contains the Keycloak subject")
  void responseShape() throws Exception {
    when(profiles.getById(TARGET)).thenReturn(user("ACTIVE"));

    mvc.perform(get(PATH + "/" + TARGET).header("Authorization", dispatcher()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TARGET.toString()))
        .andExpect(jsonPath("$.email").value("kamal@zentro.test"))
        .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
        .andExpect(jsonPath("$.keycloakSubject").doesNotExist())
        .andExpect(jsonPath("$.keycloak_subject").doesNotExist());
  }
}
