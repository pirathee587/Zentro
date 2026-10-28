package com.zentro.identitypartner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.identitypartner.merchant.CreateMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.MerchantApplication;
import com.zentro.identitypartner.merchant.AdminMerchantApplicationController;
import com.zentro.identitypartner.merchant.MerchantApplicationController;
import com.zentro.identitypartner.merchant.MerchantApplicationService;
import com.zentro.identitypartner.merchant.MerchantApiKeyController;
import com.zentro.identitypartner.merchant.MerchantApiKeyService;
import com.zentro.identitypartner.user.AdminUserController;
import com.zentro.identitypartner.user.UserProfileController;
import com.zentro.identitypartner.user.UserProfileService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {UserProfileController.class, MerchantApplicationController.class, MerchantApiKeyController.class, AdminMerchantApplicationController.class, AdminUserController.class})
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class IdentityAuthorizationTests {
  @Autowired MockMvc mvc;
  @MockBean UserProfileService profiles;
  @MockBean MerchantApplicationService applications;
  @MockBean MerchantApiKeyService apiKeys;
  @MockBean JwtDecoder jwtDecoder;

  @Test
  void userProfileRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    verifyNoInteractions(profiles);
  }

  @Test
  void aDispatcherCannotSubmitAMerchantApplication() throws Exception {
    mvc.perform(post("/api/v1/merchants")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
        .contentType("application/json")
        .content("{\"businessName\":\"Example Shop\",\"businessRegNo\":\"BR-100\",\"contactPhone\":\"0771234567\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(applications);
  }

  @Test
  void customerCanCreateMerchantApplicationWithoutChoosingAnyRole() throws Exception {
    UUID appUserId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    when(applications.createOrReapply(any(), any(CreateMerchantApplicationRequest.class)))
        .thenReturn(new MerchantApplication(applicationId, appUserId, "Example Shop", "BR-100", "0771234567", "PENDING", null, OffsetDateTime.now(), null));

    mvc.perform(post("/api/v1/merchants")
        .with(jwt().jwt(token -> token.claim("sub", "customer-subject"))
            .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType("application/json")
        .content("{\"businessName\":\"Example Shop\",\"businessRegNo\":\"BR-100\",\"contactPhone\":\"0771234567\"}"))
        .andExpect(status().isOk());

    verify(applications).createOrReapply(any(), any(CreateMerchantApplicationRequest.class));
  }

  @Test
  void onlyDispatcherCanReadUserAdministrationEndpoints() throws Exception {
    mvc.perform(get("/api/v1/admin/merchants")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(applications);
  }

  @Test
  void customerCannotCreateMerchantApiKeys() throws Exception {
    mvc.perform(post("/api/v1/merchants/api-key")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(apiKeys);
  }

  @Test
  void onlyDispatcherCanReadAdminUsersEndpoint() throws Exception {
    mvc.perform(get("/api/v1/admin/users")
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(profiles);
  }
}
