package com.zentro.identitypartner.merchant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.identitypartner.merchant.dto.CreateMerchantApplicationRequest;
import com.zentro.identitypartner.merchant.dto.MerchantApplication;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = MerchantApplicationController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class MerchantApplicationControllerTest {

  @Autowired private MockMvc mvc;
  @MockBean private MerchantApplicationService applications;
  @MockBean private JwtDecoder jwtDecoder;

  private static final UUID APP_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Nested
  @DisplayName("Authentication & Role Authorization")
  class SecurityTests {

    @Test
    void unauthenticatedCallersReceive401() throws Exception {
      mvc.perform(get("/api/v1/merchants/me"))
          .andExpect(status().isUnauthorized());

      mvc.perform(post("/api/v1/merchants")
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"businessName\":\"Acme\",\"contactPhone\":\"+94771234567\"}"))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(applications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MERCHANT", "COURIER", "DISPATCHER"})
    void nonCustomerRolesCannotSubmitApplication(String role) throws Exception {
      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role)))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"businessName\":\"Acme Store\",\"contactPhone\":\"+94771234567\"}"))
          .andExpect(status().isForbidden());

      verifyNoInteractions(applications);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER"})
    void allAuthenticatedRolesCanReadCurrentApplication(String role) throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      MerchantApplication app = new MerchantApplication(
          APP_ID, USER_ID, "Acme", "PV123", "+94771234567", "PENDING", null, null, now, null);
      when(applications.current(any(Jwt.class))).thenReturn(app);

      mvc.perform(get("/api/v1/merchants/me")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(APP_ID.toString()));

      verify(applications).current(any(Jwt.class));
    }
  }

  @Nested
  @DisplayName("Current Merchant Application Retrieval (GET /me)")
  class GetCurrentApplicationTests {

    @Test
    void customerCanReadOwnApplication() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      MerchantApplication app = new MerchantApplication(
          APP_ID, USER_ID, "Acme Retail", "PV98765", "+94771234567", "APPROVED", null, null, now, now);
      when(applications.current(any(Jwt.class))).thenReturn(app);

      mvc.perform(get("/api/v1/merchants/me")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(APP_ID.toString()))
          .andExpect(jsonPath("$.appUserId").value(USER_ID.toString()))
          .andExpect(jsonPath("$.businessName").value("Acme Retail"))
          .andExpect(jsonPath("$.businessRegNo").value("PV98765"))
          .andExpect(jsonPath("$.contactPhone").value("+94771234567"))
          .andExpect(jsonPath("$.status").value("APPROVED"));

      verify(applications).current(any(Jwt.class));
    }

    @Test
    void returns404WithProblemDetailsWhenNoApplicationExists() throws Exception {
      when(applications.current(any(Jwt.class)))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "No merchant application exists for this account."));

      mvc.perform(get("/api/v1/merchants/me")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.title").value("Not Found"))
          .andExpect(jsonPath("$.status").value(404))
          .andExpect(jsonPath("$.code").value("NOT_FOUND"))
          .andExpect(jsonPath("$.detail").value("No merchant application exists for this account."));

      verify(applications).current(any(Jwt.class));
    }
  }

  @Nested
  @DisplayName("Merchant Application Submission (POST)")
  class SubmitApplicationTests {

    @Test
    void customerCanSubmitValidApplication() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      MerchantApplication created = new MerchantApplication(
          APP_ID, USER_ID, "Ceylon Spices", "PV44556", "+94771234567", "PENDING", null, null, now, null);
      when(applications.createOrReapply(any(Jwt.class), any(CreateMerchantApplicationRequest.class)))
          .thenReturn(created);

      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"businessName\":\"Ceylon Spices\",\"businessRegNo\":\"PV44556\",\"contactPhone\":\"+94771234567\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(APP_ID.toString()))
          .andExpect(jsonPath("$.businessName").value("Ceylon Spices"))
          .andExpect(jsonPath("$.status").value("PENDING"));

      verify(applications).createOrReapply(any(Jwt.class), any(CreateMerchantApplicationRequest.class));
    }

    @Test
    void validationFailsForBlankBusinessName() throws Exception {
      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"businessName\":\"  \",\"contactPhone\":\"+94771234567\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Bad Request"))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors.businessName").exists());

      verifyNoInteractions(applications);
    }

    @Test
    void validationFailsForInvalidPhonePattern() throws Exception {
      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"businessName\":\"Acme Store\",\"contactPhone\":\"not-a-phone\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Bad Request"))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          .andExpect(jsonPath("$.fieldErrors.contactPhone").exists());

      verifyNoInteractions(applications);
    }

    @Test
    void malformedJsonBodyReturnsProblemDetails() throws Exception {
      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{invalid-json-body}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.title").value("Bad Request"))
          .andExpect(jsonPath("$.status").value(400))
          .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
          .andExpect(jsonPath("$.detail").value("The request body is missing or is not valid JSON."));

      verifyNoInteractions(applications);
    }

    @Test
    void conflictReturns409WhenApplicationAlreadyPendingOrApproved() throws Exception {
      when(applications.createOrReapply(any(Jwt.class), any(CreateMerchantApplicationRequest.class)))
          .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "A merchant application is already pending or approved."));

      mvc.perform(post("/api/v1/merchants")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"businessName\":\"Acme Store\",\"contactPhone\":\"+94771234567\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.title").value("Conflict"))
          .andExpect(jsonPath("$.status").value(409))
          .andExpect(jsonPath("$.code").value("CONFLICT"))
          .andExpect(jsonPath("$.detail").value("A merchant application is already pending or approved."));

      verify(applications).createOrReapply(any(Jwt.class), any(CreateMerchantApplicationRequest.class));
    }
  }
}
