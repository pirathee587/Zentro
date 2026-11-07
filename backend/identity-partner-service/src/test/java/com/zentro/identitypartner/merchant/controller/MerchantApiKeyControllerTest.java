package com.zentro.identitypartner.merchant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.identitypartner.merchant.dto.IssuedMerchantApiKey;
import com.zentro.identitypartner.merchant.dto.MerchantApiKeyMetadata;
import com.zentro.identitypartner.merchant.service.MerchantApiKeyService;
import com.zentro.identitypartner.merchant.service.MerchantApplicationService;
import java.time.OffsetDateTime;
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

@WebMvcTest(controllers = {
    MerchantApiKeyController.class,
    AdminMerchantApplicationController.class
})
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class MerchantApiKeyControllerTest {

  @Autowired private MockMvc mvc;
  @MockBean private MerchantApiKeyService keys;
  @MockBean private MerchantApplicationService applications;
  @MockBean private JwtDecoder jwtDecoder;

  private final UUID keyId = UUID.randomUUID();
  private final UUID applicationId = UUID.randomUUID();

  // =========================================================================
  // 1. Merchant Self-Service (/api/v1/merchants/api-key)
  // =========================================================================
  @Nested
  @DisplayName("Merchant Self-Service API Key Management")
  class MerchantSelfService {

    @Test
    @DisplayName("GET /api/v1/merchants/api-key: returns 200 with metadata for authenticated merchant")
    void merchantCanRetrieveCurrentKey() throws Exception {
      MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(
          keyId, "zn_live_abc12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true
      );
      when(keys.current(any())).thenReturn(meta);

      mvc.perform(get("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(keyId.toString()))
          .andExpect(jsonPath("$.prefix").value("zn_live_abc12345"))
          .andExpect(jsonPath("$.active").value(true));

      verify(keys).current(any());
    }

    @Test
    @DisplayName("GET /api/v1/merchants/api-key: returns 200 with empty body when no key has been generated")
    void merchantWithNoKeyReturnsEmpty() throws Exception {
      when(keys.current(any())).thenReturn(null);

      mvc.perform(get("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isOk());

      verify(keys).current(any());
    }

    @Test
    @DisplayName("GET /api/v1/merchants/api-key: returns 401 Unauthorized for unauthenticated caller")
    void unauthenticatedCallToCurrentFails() throws Exception {
      mvc.perform(get("/api/v1/merchants/api-key"))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(keys);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_COURIER", "ROLE_DISPATCHER"})
    @DisplayName("GET /api/v1/merchants/api-key: returns 403 Forbidden for non-merchant roles")
    void nonMerchantsCannotGetCurrentKey(String role) throws Exception {
      mvc.perform(get("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(keys);
    }

    @Test
    @DisplayName("POST /api/v1/merchants/api-key: returns 200 with IssuedMerchantApiKey containing secret")
    void merchantCanIssueOrRotateKey() throws Exception {
      MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(
          keyId, "zn_live_rot12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true
      );
      IssuedMerchantApiKey issued = new IssuedMerchantApiKey("zn_live_secret_key_value_123456", meta);
      when(keys.issueOrRotate(any())).thenReturn(issued);

      mvc.perform(post("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.secret").value("zn_live_secret_key_value_123456"))
          .andExpect(jsonPath("$.credential.prefix").value("zn_live_rot12345"))
          .andExpect(jsonPath("$.credential.active").value(true));

      verify(keys).issueOrRotate(any());
    }

    @Test
    @DisplayName("DELETE /api/v1/merchants/api-key: returns 200 with revoked metadata")
    void merchantCanRevokeKey() throws Exception {
      MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(
          keyId, "zn_live_rot12345", OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), false
      );
      when(keys.revoke(any())).thenReturn(meta);

      mvc.perform(delete("/api/v1/merchants/api-key")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.active").value(false))
          .andExpect(jsonPath("$.revokedAt").isNotEmpty());

      verify(keys).revoke(any());
    }
  }

  // =========================================================================
  // 2. Admin Operations (/api/v1/admin/merchants/{applicationId}/api-key)
  // =========================================================================
  @Nested
  @DisplayName("Admin Merchant API Key Management")
  class AdminManagement {

    @Test
    @DisplayName("GET /api/v1/admin/merchants/{id}/api-key: returns 200 with metadata for DISPATCHER")
    void dispatcherCanViewMerchantKey() throws Exception {
      MerchantApiKeyMetadata meta = new MerchantApiKeyMetadata(
          keyId, "zn_live_adm12345", OffsetDateTime.now(), OffsetDateTime.now(), null, true
      );
      when(keys.getByApplicationId(eq(applicationId))).thenReturn(meta);

      mvc.perform(get("/api/v1/admin/merchants/{id}/api-key", applicationId)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(keyId.toString()))
          .andExpect(jsonPath("$.prefix").value("zn_live_adm12345"))
          .andExpect(jsonPath("$.active").value(true));

      verify(keys).getByApplicationId(eq(applicationId));
    }

    @Test
    @DisplayName("GET /api/v1/admin/merchants/{id}/api-key: returns 404 when application or key not found")
    void dispatcherReceives404WhenNotFound() throws Exception {
      when(keys.getByApplicationId(eq(applicationId)))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant application was not found."));

      mvc.perform(get("/api/v1/admin/merchants/{id}/api-key", applicationId)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_MERCHANT", "ROLE_CUSTOMER", "ROLE_COURIER"})
    @DisplayName("GET /api/v1/admin/merchants/{id}/api-key: returns 403 Forbidden for non-dispatcher roles")
    void nonDispatchersCannotViewAdminKey(String role) throws Exception {
      mvc.perform(get("/api/v1/admin/merchants/{id}/api-key", applicationId)
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(keys);
    }

    @Test
    @DisplayName("DELETE /api/v1/admin/merchants/{id}/api-key: returns 200 with revoked metadata for DISPATCHER")
    void dispatcherCanRevokeMerchantKey() throws Exception {
      MerchantApiKeyMetadata revoked = new MerchantApiKeyMetadata(
          keyId, "zn_live_adm12345", OffsetDateTime.now(), OffsetDateTime.now(), OffsetDateTime.now(), false
      );
      when(keys.revokeByApplicationId(eq(applicationId))).thenReturn(revoked);

      mvc.perform(delete("/api/v1/admin/merchants/{id}/api-key", applicationId)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.active").value(false))
          .andExpect(jsonPath("$.revokedAt").isNotEmpty());

      verify(keys).revokeByApplicationId(eq(applicationId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_MERCHANT", "ROLE_CUSTOMER", "ROLE_COURIER"})
    @DisplayName("DELETE /api/v1/admin/merchants/{id}/api-key: returns 403 Forbidden for non-dispatcher roles")
    void nonDispatchersCannotRevokeAdminKey(String role) throws Exception {
      mvc.perform(delete("/api/v1/admin/merchants/{id}/api-key", applicationId)
          .with(jwt().authorities(new SimpleGrantedAuthority(role))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(keys);
    }
  }
}
