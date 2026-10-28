package com.zentro.identitypartner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.identitypartner.merchant.AdminMerchantApplicationController;
import com.zentro.identitypartner.merchant.CreateMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.IssuedMerchantApiKey;
import com.zentro.identitypartner.merchant.MerchantApiKeyController;
import com.zentro.identitypartner.merchant.MerchantApiKeyMetadata;
import com.zentro.identitypartner.merchant.MerchantApiKeyService;
import com.zentro.identitypartner.merchant.MerchantApplication;
import com.zentro.identitypartner.merchant.MerchantApplicationController;
import com.zentro.identitypartner.merchant.MerchantApplicationService;
import com.zentro.identitypartner.merchant.ReviewMerchantApplicationRequest;
import com.zentro.identitypartner.user.UpdateUserProfileRequest;
import com.zentro.identitypartner.user.UserProfile;
import com.zentro.identitypartner.user.UserProfileController;
import com.zentro.identitypartner.user.UserProfileService;
import java.time.OffsetDateTime;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies role-based authorization rules and HTTP 401/403 status behavior
 * for the Zentro identity and partner endpoints across the 4 human roles:
 * CUSTOMER, MERCHANT, COURIER, DISPATCHER (which also handles administrative actions).
 */
@WebMvcTest(controllers = {
    UserProfileController.class,
    MerchantApplicationController.class,
    MerchantApiKeyController.class,
    AdminMerchantApplicationController.class
})
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class RoleBasedAuthorizationMatrixTests {

  @Autowired private MockMvc mvc;
  @MockBean private UserProfileService profiles;
  @MockBean private MerchantApplicationService applications;
  @MockBean private MerchantApiKeyService apiKeys;
  @MockBean private JwtDecoder jwtDecoder;

  private static final UUID TEST_APP_ID = UUID.randomUUID();
  private static final UUID TEST_USER_ID = UUID.randomUUID();

  // =========================================================================
  // 1. Authentication Tests (Unauthenticated -> 401)
  // =========================================================================
  @Nested
  @DisplayName("Authentication (401 Unauthorized for unauthenticated callers)")
  class UnauthenticatedRequests {

    @Test
    void userProfileGetRequiresAuth() throws Exception {
      mvc.perform(get("/api/v1/users/me"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(profiles);
    }

    @Test
    void userProfilePatchRequiresAuth() throws Exception {
      mvc.perform(patch("/api/v1/users/me")
          .contentType("application/json")
          .content("{\"name\":\"New Name\"}"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(profiles);
    }

    @Test
    void merchantApplicationGetRequiresAuth() throws Exception {
      mvc.perform(get("/api/v1/merchants/me"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(applications);
    }

    @Test
    void merchantApplicationPostRequiresAuth() throws Exception {
      mvc.perform(post("/api/v1/merchants")
          .contentType("application/json")
          .content("{\"businessName\":\"Shop\",\"contactPhone\":\"0771234567\"}"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(applications);
    }

    @Test
    void merchantApiKeyGetRequiresAuth() throws Exception {
      mvc.perform(get("/api/v1/merchants/api-key"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(apiKeys);
    }

    @Test
    void merchantApiKeyPostRequiresAuth() throws Exception {
      mvc.perform(post("/api/v1/merchants/api-key"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(apiKeys);
    }

    @Test
    void merchantApiKeyDeleteRequiresAuth() throws Exception {
      mvc.perform(delete("/api/v1/merchants/api-key"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(apiKeys);
    }

    @Test
    void adminMerchantListRequiresAuth() throws Exception {
      mvc.perform(get("/api/v1/admin/merchants"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(applications);
    }

    @Test
    void adminMerchantReviewRequiresAuth() throws Exception {
      mvc.perform(patch("/api/v1/admin/merchants/" + TEST_APP_ID)
          .contentType("application/json")
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isUnauthorized());
      verifyNoInteractions(applications);
    }
  }

  // =========================================================================
  // 2. Administrative Endpoints (/api/v1/admin/merchants)
  //    Only DISPATCHER is permitted; CUSTOMER, MERCHANT, COURIER receive 403.
  // =========================================================================
  @Nested
  @DisplayName("Admin Merchant Review Endpoints (/api/v1/admin/merchants)")
  class AdminMerchantEndpoints {

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER"})
    void nonDispatcherRolesCannotListPendingApplications(String role) throws Exception {
      mvc.perform(get("/api/v1/admin/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());
      verifyNoInteractions(applications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER"})
    void nonDispatcherRolesCannotReviewApplications(String role) throws Exception {
      mvc.perform(patch("/api/v1/admin/merchants/" + TEST_APP_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority(role)))
          .contentType("application/json")
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isForbidden());
      verifyNoInteractions(applications);
    }

    @Test
    void dispatcherCanListPendingMerchantApplications() throws Exception {
      when(applications.listPending()).thenReturn(List.of(
          new MerchantApplication(TEST_APP_ID, TEST_USER_ID, "Tech Store", "BR-101", "0770001111", "PENDING", null, OffsetDateTime.now(), null)
      ));

      mvc.perform(get("/api/v1/admin/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$[0].businessName").value("Tech Store"));

      verify(applications).listPending();
    }

    @Test
    void dispatcherCanReviewMerchantApplication() throws Exception {
      when(applications.review(eq(TEST_APP_ID), any(), any(ReviewMerchantApplicationRequest.class)))
          .thenReturn(new MerchantApplication(TEST_APP_ID, TEST_USER_ID, "Tech Store", "BR-101", "0770001111", "APPROVED", null, OffsetDateTime.now(), OffsetDateTime.now()));

      mvc.perform(patch("/api/v1/admin/merchants/" + TEST_APP_ID)
          .with(jwt().jwt(t -> t.subject("dispatcher-sub"))
              .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("APPROVED"));

      verify(applications).review(eq(TEST_APP_ID), any(), any(ReviewMerchantApplicationRequest.class));
    }
  }

  // =========================================================================
  // 3. Merchant API Key Endpoints (/api/v1/merchants/api-key)
  //    Only MERCHANT is permitted; CUSTOMER, COURIER, DISPATCHER receive 403.
  // =========================================================================
  @Nested
  @DisplayName("Merchant API Key Endpoints (/api/v1/merchants/api-key)")
  class MerchantApiKeyEndpoints {

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_COURIER", "ROLE_DISPATCHER"})
    void nonMerchantsCannotGetApiKey(String role) throws Exception {
      mvc.perform(get("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());
      verifyNoInteractions(apiKeys);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_COURIER", "ROLE_DISPATCHER"})
    void nonMerchantsCannotIssueApiKey(String role) throws Exception {
      mvc.perform(post("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());
      verifyNoInteractions(apiKeys);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_COURIER", "ROLE_DISPATCHER"})
    void nonMerchantsCannotRevokeApiKey(String role) throws Exception {
      mvc.perform(delete("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());
      verifyNoInteractions(apiKeys);
    }

    @Test
    void merchantCanGetApiKeyMetadata() throws Exception {
      when(apiKeys.current(any())).thenReturn(
          new MerchantApiKeyMetadata(UUID.randomUUID(), "zn_live_abc12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true)
      );

      mvc.perform(get("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.prefix").value("zn_live_abc12345"))
          .andExpect(jsonPath("$.active").value(true));

      verify(apiKeys).current(any());
    }

    @Test
    void merchantCanIssueOrRotateApiKey() throws Exception {
      MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(UUID.randomUUID(), "zn_live_rot12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true);
      when(apiKeys.issueOrRotate(any())).thenReturn(new IssuedMerchantApiKey("zn_live_secret_token", meta));

      mvc.perform(post("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.secret").value("zn_live_secret_token"))
          .andExpect(jsonPath("$.credential.prefix").value("zn_live_rot12345"));

      verify(apiKeys).issueOrRotate(any());
    }

    @Test
    void merchantCanRevokeApiKey() throws Exception {
      MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(UUID.randomUUID(), "zn_live_rot12345", OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), false);
      when(apiKeys.revoke(any())).thenReturn(meta);

      mvc.perform(delete("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.active").value(false));

      verify(apiKeys).revoke(any());
    }
  }

  // =========================================================================
  // 4. Merchant Application Creation (/api/v1/merchants)
  //    Only CUSTOMER can apply; MERCHANT, COURIER, DISPATCHER receive 403.
  // =========================================================================
  @Nested
  @DisplayName("Merchant Application Submission (/api/v1/merchants)")
  class MerchantApplicationSubmission {

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER"})
    void nonCustomerRolesCannotSubmitMerchantApplication(String role) throws Exception {
      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority(role)))
          .contentType("application/json")
          .content("{\"businessName\":\"Store\",\"contactPhone\":\"0771234567\"}"))
          .andExpect(status().isForbidden());
      verifyNoInteractions(applications);
    }

    @Test
    void customerCanSubmitMerchantApplication() throws Exception {
      when(applications.createOrReapply(any(), any(CreateMerchantApplicationRequest.class)))
          .thenReturn(new MerchantApplication(TEST_APP_ID, TEST_USER_ID, "My Store", null, "0771234567", "PENDING", null, OffsetDateTime.now(), null));

      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType("application/json")
          .content("{\"businessName\":\"My Store\",\"contactPhone\":\"0771234567\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("PENDING"));

      verify(applications).createOrReapply(any(), any(CreateMerchantApplicationRequest.class));
    }
  }

  // =========================================================================
  // 5. User Profile Endpoints (/api/v1/users/me)
  //    Accessible by all four human roles (CUSTOMER, MERCHANT, COURIER, DISPATCHER).
  // =========================================================================
  @Nested
  @DisplayName("User Profile Endpoints (/api/v1/users/me)")
  class UserProfileEndpoints {

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER"})
    void allHumanRolesCanReadOwnProfile(String role) throws Exception {
      when(profiles.current(any())).thenReturn(
          new UserProfile(TEST_USER_ID, "user@zentro.com", "Zentro User", "0771234567", true, "ACTIVE", null, List.of(role.replace("ROLE_", "")))
      );

      mvc.perform(get("/api/v1/users/me")
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.email").value("user@zentro.com"));

      verify(profiles).current(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER"})
    void allHumanRolesCanUpdateOwnProfile(String role) throws Exception {
      when(profiles.update(any(), any(UpdateUserProfileRequest.class))).thenReturn(
          new UserProfile(TEST_USER_ID, "user@zentro.com", "Updated Name", "0779998888", true, "ACTIVE", null, List.of(role.replace("ROLE_", "")))
      );

      mvc.perform(patch("/api/v1/users/me")
          .with(jwt().authorities(new SimpleGrantedAuthority(role)))
          .contentType("application/json")
          .content("{\"name\":\"Updated Name\",\"phone\":\"0779998888\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.name").value("Updated Name"));

      verify(profiles).update(any(), any(UpdateUserProfileRequest.class));
    }
  }
}
