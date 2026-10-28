package com.zentro.identitypartner.user;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = AdminUserController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class AdminUserControllerTest {

  @Autowired private MockMvc mvc;
  @MockBean private UserProfileService profiles;
  @MockBean private JwtDecoder jwtDecoder;

  private static final UUID TARGET_USER_ID = UUID.randomUUID();

  @Nested
  @DisplayName("Authentication requirements (401)")
  class AuthenticationTests {

    @Test
    void listUsersRequiresAuthentication() throws Exception {
      mvc.perform(get("/api/v1/admin/users"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(profiles);
    }

    @Test
    void getUserByIdRequiresAuthentication() throws Exception {
      mvc.perform(get("/api/v1/admin/users/" + TARGET_USER_ID))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(profiles);
    }

    @Test
    void updateUserRequiresAuthentication() throws Exception {
      mvc.perform(patch("/api/v1/admin/users/" + TARGET_USER_ID)
          .contentType("application/json")
          .content("{\"accountStatus\":\"DISABLED\"}"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(profiles);
    }
  }

  @Nested
  @DisplayName("Authorization requirements (403 for non-DISPATCHER)")
  class AuthorizationTests {

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER"})
    void nonDispatcherRolesCannotListUsers(String role) throws Exception {
      mvc.perform(get("/api/v1/admin/users")
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());
      verifyNoInteractions(profiles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER"})
    void nonDispatcherRolesCannotGetUserById(String role) throws Exception {
      mvc.perform(get("/api/v1/admin/users/" + TARGET_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());
      verifyNoInteractions(profiles);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER"})
    void nonDispatcherRolesCannotUpdateUser(String role) throws Exception {
      mvc.perform(patch("/api/v1/admin/users/" + TARGET_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority(role)))
          .contentType("application/json")
          .content("{\"accountStatus\":\"DISABLED\"}"))
          .andExpect(status().isForbidden());
      verifyNoInteractions(profiles);
    }
  }

  @Nested
  @DisplayName("Dispatcher operations (200, 400, 404)")
  class DispatcherOperations {

    @Test
    void dispatcherCanListUsers() throws Exception {
      when(profiles.listUsers(eq("ACTIVE"), eq("smith"))).thenReturn(List.of(
          new UserProfile(TARGET_USER_ID, "smith@zentro.com", "John Smith", "0771234567", true, "ACTIVE", null, List.of("CUSTOMER"))
      ));

      mvc.perform(get("/api/v1/admin/users")
          .param("status", "ACTIVE")
          .param("search", "smith")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].id").value(TARGET_USER_ID.toString()))
          .andExpect(jsonPath("$[0].email").value("smith@zentro.com"))
          .andExpect(jsonPath("$[0].accountStatus").value("ACTIVE"))
          .andExpect(jsonPath("$[0].enabled").value(true));

      verify(profiles).listUsers(eq("ACTIVE"), eq("smith"));
    }

    @Test
    void dispatcherCanGetUserById() throws Exception {
      when(profiles.getById(TARGET_USER_ID)).thenReturn(
          new UserProfile(TARGET_USER_ID, "smith@zentro.com", "John Smith", "0771234567", true, "ACTIVE", null, List.of("CUSTOMER"))
      );

      mvc.perform(get("/api/v1/admin/users/" + TARGET_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(TARGET_USER_ID.toString()))
          .andExpect(jsonPath("$.email").value("smith@zentro.com"));

      verify(profiles).getById(TARGET_USER_ID);
    }

    @Test
    void dispatcherReceives404WhenUserNotFound() throws Exception {
      when(profiles.getById(TARGET_USER_ID))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

      mvc.perform(get("/api/v1/admin/users/" + TARGET_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isNotFound());

      verify(profiles).getById(TARGET_USER_ID);
    }

    @Test
    void dispatcherCanUpdateUserStatusAndDetails() throws Exception {
      when(profiles.adminUpdate(eq(TARGET_USER_ID), any(), any(AdminUpdateUserRequest.class))).thenReturn(
          new UserProfile(TARGET_USER_ID, "smith@zentro.com", "John Smith Updated", "0779998888", false, "DISABLED", null, List.of("CUSTOMER"))
      );

      mvc.perform(patch("/api/v1/admin/users/" + TARGET_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"name\":\"John Smith Updated\",\"phone\":\"0779998888\",\"accountStatus\":\"DISABLED\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("John Smith Updated"))
          .andExpect(jsonPath("$.phone").value("0779998888"))
          .andExpect(jsonPath("$.accountStatus").value("DISABLED"))
          .andExpect(jsonPath("$.enabled").value(false));

      verify(profiles).adminUpdate(eq(TARGET_USER_ID), any(), any(AdminUpdateUserRequest.class));
    }

    @Test
    void invalidAccountStatusReturns400() throws Exception {
      mvc.perform(patch("/api/v1/admin/users/" + TARGET_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"accountStatus\":\"SUPERUSER\"}"))
          .andExpect(status().isBadRequest());

      verifyNoInteractions(profiles);
    }

    @Test
    void selfLockoutAttemptReturns400() throws Exception {
      when(profiles.adminUpdate(eq(TARGET_USER_ID), any(), any(AdminUpdateUserRequest.class)))
          .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Administrators cannot disable or suspend their own account."));

      mvc.perform(patch("/api/v1/admin/users/" + TARGET_USER_ID)
          .with(jwt().jwt(t -> t.subject("dispatcher-self-sub"))
              .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"accountStatus\":\"DISABLED\"}"))
          .andExpect(status().isBadRequest());

      verify(profiles).adminUpdate(eq(TARGET_USER_ID), any(), any(AdminUpdateUserRequest.class));
    }
  }
}

