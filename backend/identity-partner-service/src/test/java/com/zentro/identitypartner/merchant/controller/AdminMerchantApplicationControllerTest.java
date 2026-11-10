package com.zentro.identitypartner.merchant.controller;

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

import com.zentro.identitypartner.merchant.dto.MerchantApplication;
import com.zentro.identitypartner.merchant.dto.ReviewMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.SuspendMerchantRequest;
import com.zentro.identitypartner.merchant.service.MerchantApiKeyService;
import com.zentro.identitypartner.merchant.service.MerchantApplicationService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = AdminMerchantApplicationController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class AdminMerchantApplicationControllerTest {

  @Autowired private MockMvc mvc;
  @MockBean private MerchantApplicationService applications;
  @MockBean private MerchantApiKeyService keys;
  @MockBean private JwtDecoder jwtDecoder;

  private static final UUID APP_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Nested
  @DisplayName("Authentication & Dispatcher Role Authorization")
  class SecurityTests {

    @Test
    void unauthenticatedCallersReceive401() throws Exception {
      mvc.perform(get("/api/v1/admin/merchants"))
          .andExpect(status().isUnauthorized());

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID)
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isUnauthorized());

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID + "/suspend")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"reason\":\"Violation\"}"))
          .andExpect(status().isUnauthorized());

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID + "/reinstate"))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(applications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER"})
    void nonDispatcherRolesCannotAccessAdminEndpoints(String role) throws Exception {
      mvc.perform(get("/api/v1/admin/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
          .andExpect(status().isForbidden());

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isForbidden());

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID + "/suspend")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"reason\":\"Policy violation\"}"))
          .andExpect(status().isForbidden());

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID + "/reinstate")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(applications);
    }
  }

  @Nested
  @DisplayName("List Merchant Applications (GET /api/v1/admin/merchants)")
  class ListApplicationsTests {

    @Test
    void dispatcherCanListAllApplications() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      when(applications.list(null)).thenReturn(List.of(
          new MerchantApplication(APP_ID, USER_ID, "Shop One", "PV111", "+94771112233", "PENDING", null, null, now, null)
      ));

      mvc.perform(get("/api/v1/admin/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].businessName").value("Shop One"));

      verify(applications).list(null);
    }

    @Test
    void dispatcherCanListApplicationsWithValidStatusFilter() throws Exception {
      when(applications.list("PENDING")).thenReturn(List.of());

      mvc.perform(get("/api/v1/admin/merchants")
          .param("status", "PENDING")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(0));

      verify(applications).list("PENDING");
    }

    @Test
    void invalidStatusFilterReturnsBadRequestWithProblemDetails() throws Exception {
      when(applications.list("INVALID_STATUS"))
          .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "Invalid status filter. Must be one of: PENDING, APPROVED, REJECTED, SUSPENDED."));

      mvc.perform(get("/api/v1/admin/merchants")
          .param("status", "INVALID_STATUS")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Bad Request"))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.detail").value("Invalid status filter. Must be one of: PENDING, APPROVED, REJECTED, SUSPENDED."));

      verify(applications).list("INVALID_STATUS");
    }
  }

  @Nested
  @DisplayName("Review Merchant Application (PATCH /{applicationId})")
  class ReviewApplicationTests {

    @Test
    void dispatcherCanApproveApplication() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      MerchantApplication approved = new MerchantApplication(
          APP_ID, USER_ID, "Shop One", "PV111", "+94771112233", "APPROVED", null, null, now, now);
      when(applications.review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class)))
          .thenReturn(approved);

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(APP_ID.toString()))
          .andExpect(jsonPath("$.status").value("APPROVED"));

      verify(applications).review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class));
    }

    @Test
    void dispatcherCanRejectApplicationWithReason() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      MerchantApplication rejected = new MerchantApplication(
          APP_ID, USER_ID, "Shop One", "PV111", "+94771112233", "REJECTED", "Incomplete documents", null, now, now);
      when(applications.review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class)))
          .thenReturn(rejected);

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"decision\":\"REJECTED\",\"rejectionReason\":\"Incomplete documents\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(APP_ID.toString()))
          .andExpect(jsonPath("$.status").value("REJECTED"))
          .andExpect(jsonPath("$.rejectionReason").value("Incomplete documents"));

      verify(applications).review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class));
    }

    @Test
    void validationFailsWhenDecisionIsNull() throws Exception {
      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"rejectionReason\":\"Incomplete documents\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Bad Request"))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors.decision").exists());

      verifyNoInteractions(applications);
    }

    @Test
    void invalidApplicationIdUuidReturnsBadRequest() throws Exception {
      mvc.perform(patch("/api/v1/admin/merchants/not-a-valid-uuid")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Bad Request"))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("INVALID_APPLICATION_ID"))
          .andExpect(jsonPath("$.detail").value("The application id must be a UUID."));

      verifyNoInteractions(applications);
    }

    @Test
    void notFoundReturns404WithProblemDetails() throws Exception {
      when(applications.review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class)))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Merchant application not found: " + APP_ID));

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.title").value("Not Found"))
          .andExpect(jsonPath("$.status").value(404))
          .andExpect(jsonPath("$.code").value("NOT_FOUND"))
          .andExpect(jsonPath("$.detail").value("Merchant application not found: " + APP_ID));

      verify(applications).review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class));
    }

    @Test
    void conflictReturns409WhenApplicationNotInPendingState() throws Exception {
      when(applications.review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class)))
          .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Application is not in PENDING state."));

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"decision\":\"APPROVED\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.title").value("Conflict"))
          .andExpect(jsonPath("$.status").value(409))
          .andExpect(jsonPath("$.code").value("CONFLICT"))
          .andExpect(jsonPath("$.detail").value("Application is not in PENDING state."));

      verify(applications).review(eq(APP_ID), any(Jwt.class), any(ReviewMerchantApplicationRequest.class));
    }
  }

  @Nested
  @DisplayName("Suspend Merchant (PATCH /{applicationId}/suspend)")
  class SuspendMerchantTests {

    @Test
    void dispatcherCanSuspendMerchantWithReason() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      MerchantApplication suspended = new MerchantApplication(
          APP_ID, USER_ID, "Shop One", "PV111", "+94771112233", "SUSPENDED", null, "Breach of TOS", now, now);
      when(applications.suspend(eq(APP_ID), any(Jwt.class), eq("Breach of TOS")))
          .thenReturn(suspended);

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID + "/suspend")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"reason\":\"Breach of TOS\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(APP_ID.toString()))
          .andExpect(jsonPath("$.status").value("SUSPENDED"))
          .andExpect(jsonPath("$.suspensionReason").value("Breach of TOS"));

      verify(applications).suspend(eq(APP_ID), any(Jwt.class), eq("Breach of TOS"));
    }

    @Test
    void validationFailsForBlankSuspensionReason() throws Exception {
      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID + "/suspend")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"reason\":\"   \"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Bad Request"))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors.reason").exists());

      verifyNoInteractions(applications);
    }
  }

  @Nested
  @DisplayName("Reinstate Merchant (PATCH /{applicationId}/reinstate)")
  class ReinstateMerchantTests {

    @Test
    void dispatcherCanReinstateSuspendedMerchant() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      MerchantApplication reinstated = new MerchantApplication(
          APP_ID, USER_ID, "Shop One", "PV111", "+94771112233", "APPROVED", null, null, now, now);
      when(applications.reinstate(eq(APP_ID), any(Jwt.class)))
          .thenReturn(reinstated);

      mvc.perform(patch("/api/v1/admin/merchants/" + APP_ID + "/reinstate")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(APP_ID.toString()))
          .andExpect(jsonPath("$.status").value("APPROVED"));

      verify(applications).reinstate(eq(APP_ID), any(Jwt.class));
    }
  }
}
