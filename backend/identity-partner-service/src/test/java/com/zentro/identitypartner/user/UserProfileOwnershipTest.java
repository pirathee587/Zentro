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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = UserProfileController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class UserProfileOwnershipTest {

  @Autowired private MockMvc mvc;
  @MockBean private UserProfileService profiles;
  @MockBean private JwtDecoder jwtDecoder;

  private static final UUID OWN_USER_ID = UUID.randomUUID();
  private static final UUID OTHER_USER_ID = UUID.randomUUID();

  @Nested
  @DisplayName("Self profile (/api/v1/users/me)")
  class SelfProfileTests {

    @Test
    void unauthenticatedCallerReceives401() throws Exception {
      mvc.perform(get("/api/v1/users/me"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(profiles);
    }

    @Test
    void authenticatedUserCanReadOwnProfile() throws Exception {
      when(profiles.current(any())).thenReturn(
          new UserProfile(OWN_USER_ID, "user@zentro.com", "Own User", "0771234567", true, "ACTIVE", null, List.of("CUSTOMER"))
      );

      mvc.perform(get("/api/v1/users/me")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(OWN_USER_ID.toString()))
          .andExpect(jsonPath("$.email").value("user@zentro.com"));

      verify(profiles).current(any());
    }

    @Test
    void authenticatedUserCanUpdateOwnProfile() throws Exception {
      when(profiles.update(any(), any(UpdateUserProfileRequest.class))).thenReturn(
          new UserProfile(OWN_USER_ID, "user@zentro.com", "Updated User", "0779998888", true, "ACTIVE", null, List.of("CUSTOMER"))
      );

      mvc.perform(patch("/api/v1/users/me")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType("application/json")
          .content("{\"name\":\"Updated User\",\"phone\":\"0779998888\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Updated User"))
          .andExpect(jsonPath("$.phone").value("0779998888"));

      verify(profiles).update(any(), any(UpdateUserProfileRequest.class));
    }
  }

  @Nested
  @DisplayName("Ownership and Cross-User Enforcement (/api/v1/users/{userId})")
  class CrossUserOwnershipTests {

    @Test
    void userCanReadOwnProfileByExplicitId() throws Exception {
      when(profiles.getByIdForCaller(any(), eq(OWN_USER_ID))).thenReturn(
          new UserProfile(OWN_USER_ID, "user@zentro.com", "Own User", "0771234567", true, "ACTIVE", null, List.of("CUSTOMER"))
      );

      mvc.perform(get("/api/v1/users/" + OWN_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(OWN_USER_ID.toString()));

      verify(profiles).getByIdForCaller(any(), eq(OWN_USER_ID));
    }

    @Test
    void crossUserReadAttemptReturns403Forbidden() throws Exception {
      when(profiles.getByIdForCaller(any(), eq(OTHER_USER_ID)))
          .thenThrow(new AccessDeniedException("Access denied: cannot view another user's profile."));

      mvc.perform(get("/api/v1/users/" + OTHER_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isForbidden());

      verify(profiles).getByIdForCaller(any(), eq(OTHER_USER_ID));
    }

    @Test
    void userCanUpdateOwnProfileByExplicitId() throws Exception {
      when(profiles.updateByIdForCaller(any(), eq(OWN_USER_ID), any(UpdateUserProfileRequest.class))).thenReturn(
          new UserProfile(OWN_USER_ID, "user@zentro.com", "Updated User", "0779998888", true, "ACTIVE", null, List.of("CUSTOMER"))
      );

      mvc.perform(patch("/api/v1/users/" + OWN_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType("application/json")
          .content("{\"name\":\"Updated User\",\"phone\":\"0779998888\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Updated User"));

      verify(profiles).updateByIdForCaller(any(), eq(OWN_USER_ID), any(UpdateUserProfileRequest.class));
    }

    @Test
    void crossUserUpdateAttemptReturns403Forbidden() throws Exception {
      when(profiles.updateByIdForCaller(any(), eq(OTHER_USER_ID), any(UpdateUserProfileRequest.class)))
          .thenThrow(new AccessDeniedException("Access denied: cannot update another user's profile."));

      mvc.perform(patch("/api/v1/users/" + OTHER_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType("application/json")
          .content("{\"name\":\"Malicious Cross-User Edit\"}"))
          .andExpect(status().isForbidden());

      verify(profiles).updateByIdForCaller(any(), eq(OTHER_USER_ID), any(UpdateUserProfileRequest.class));
    }

    @Test
    void dispatcherCanReadAnyUserProfileById() throws Exception {
      when(profiles.getByIdForCaller(any(), eq(OTHER_USER_ID))).thenReturn(
          new UserProfile(OTHER_USER_ID, "other@zentro.com", "Other User", "0770001111", true, "ACTIVE", null, List.of("CUSTOMER"))
      );

      mvc.perform(get("/api/v1/users/" + OTHER_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(OTHER_USER_ID.toString()));

      verify(profiles).getByIdForCaller(any(), eq(OTHER_USER_ID));
    }

    @Test
    void dispatcherCanUpdateAnyUserProfileById() throws Exception {
      when(profiles.updateByIdForCaller(any(), eq(OTHER_USER_ID), any(UpdateUserProfileRequest.class))).thenReturn(
          new UserProfile(OTHER_USER_ID, "other@zentro.com", "Dispatcher Edited", "0770001111", true, "ACTIVE", null, List.of("CUSTOMER"))
      );

      mvc.perform(patch("/api/v1/users/" + OTHER_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"name\":\"Dispatcher Edited\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Dispatcher Edited"));

      verify(profiles).updateByIdForCaller(any(), eq(OTHER_USER_ID), any(UpdateUserProfileRequest.class));
    }

    @Test
    void notFoundUserReturns404() throws Exception {
      when(profiles.getByIdForCaller(any(), eq(OTHER_USER_ID)))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

      mvc.perform(get("/api/v1/users/" + OTHER_USER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isNotFound());

      verify(profiles).getByIdForCaller(any(), eq(OTHER_USER_ID));
    }
  }
}

