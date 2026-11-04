package com.zentro.parcelpricing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.error.InvalidTransitionException;
import com.zentro.parcelpricing.error.ParcelNotFoundException;
import com.zentro.parcelpricing.error.StatusExceptionHandler;
import com.zentro.parcelpricing.error.StatusFieldException;
import com.zentro.parcelpricing.service.ParcelStatusService;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = ParcelStatusController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class, StatusExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class ParcelStatusControllerTest {
  private static final UUID PARCEL_ID = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
  private static final String URL = "/api/v1/parcels/" + PARCEL_ID + "/status";
  private static final String PROBLEM_JSON = "application/problem+json";

  @Autowired MockMvc mvc;
  @MockBean ParcelStatusService statusService;
  @MockBean JwtDecoder jwtDecoder;

  private static RequestPostProcessor as(String subject, String... roles) {
    SimpleGrantedAuthority[] authorities = new SimpleGrantedAuthority[roles.length];
    for (int i = 0; i < roles.length; i++) {
      authorities[i] = new SimpleGrantedAuthority("ROLE_" + roles[i]);
    }
    return jwt().jwt(j -> j.subject(subject)).authorities(authorities);
  }

  private ResultActions send(RequestPostProcessor auth, String url, String body) throws Exception {
    return mvc.perform(patch(url).with(auth).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  private static ParcelStatusResponse changed(ParcelStatus from, ParcelStatus to) {
    return new ParcelStatusResponse(PARCEL_ID, "ZT-ABCDEFGH12", from, to, true,
        OffsetDateTime.parse("2026-11-03T10:15:30.123456Z"));
  }

  @Test
  @DisplayName("Dispatcher changes the status: 200 and the actor is the token subject with role DISPATCHER")
  void dispatcherCanChangeStatus() throws Exception {
    when(statusService.updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, "Gate 2", "dispatcher-1", "DISPATCHER"))
        .thenReturn(changed(ParcelStatus.CREATED, ParcelStatus.PICKED_UP));

    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"PICKED_UP\",\"note\":\"Gate 2\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.parcelId").value(PARCEL_ID.toString()))
        .andExpect(jsonPath("$.trackingRef").value("ZT-ABCDEFGH12"))
        .andExpect(jsonPath("$.previousStatus").value("CREATED"))
        .andExpect(jsonPath("$.status").value("PICKED_UP"))
        .andExpect(jsonPath("$.changed").value(true))
        .andExpect(jsonPath("$.changedAt").value("2026-11-03T10:15:30.123456Z"));
  }

  @Test
  @DisplayName("Internal service token changes the status with role SERVICE")
  void serviceCanChangeStatus() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenReturn(changed(ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT));

    send(as("service-account-s3", "SERVICE"), URL, "{\"status\":\"IN_TRANSIT\"}").andExpect(status().isOk());

    verify(statusService).updateStatus(PARCEL_ID, ParcelStatus.IN_TRANSIT, null, null, "service-account-s3", "SERVICE");
  }

  @Test
  @DisplayName("A token with both DISPATCHER and SERVICE is recorded as DISPATCHER")
  void dispatcherRoleWinsOverService() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenReturn(changed(ParcelStatus.CREATED, ParcelStatus.PICKED_UP));

    send(as("both-1", "SERVICE", "DISPATCHER"), URL, "{\"status\":\"PICKED_UP\"}").andExpect(status().isOk());

    verify(statusService).updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, null, "both-1", "DISPATCHER");
  }

  @Test
  @DisplayName("Actor fields in the body are ignored; the token decides who made the change")
  void actorComesFromToken() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenReturn(changed(ParcelStatus.CREATED, ParcelStatus.PICKED_UP));

    send(as("real-dispatcher", "DISPATCHER"), URL,
        "{\"status\":\"PICKED_UP\",\"actorSubject\":\"someone-else\",\"actorRole\":\"SERVICE\"}")
        .andExpect(status().isOk());

    verify(statusService).updateStatus(PARCEL_ID, ParcelStatus.PICKED_UP, null, null, "real-dispatcher", "DISPATCHER");
  }

  @Test
  @DisplayName("Repeating the current status returns 200 with changed=false")
  void repeatReturnsUnchanged() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenReturn(new ParcelStatusResponse(PARCEL_ID, "ZT-ABCDEFGH12", ParcelStatus.PICKED_UP,
            ParcelStatus.PICKED_UP, false, null));

    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"PICKED_UP\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.changed").value(false))
        .andExpect(jsonPath("$.changedAt").doesNotExist());
  }

  @Test
  @DisplayName("No token returns 401")
  void anonymousReturns401() throws Exception {
    mvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PICKED_UP\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(statusService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER"})
  @DisplayName("Customer, merchant and courier tokens return 403")
  void otherRolesReturn403(String role) throws Exception {
    send(as("user-1", role), URL, "{\"status\":\"PICKED_UP\"}").andExpect(status().isForbidden());
    verifyNoInteractions(statusService);
  }

  @Test
  @DisplayName("Malformed JSON returns 400 MALFORMED_REQUEST")
  void malformedJsonReturns400() throws Exception {
    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\": ")
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    verifyNoInteractions(statusService);
  }

  @Test
  @DisplayName("Unknown status value returns 400 MALFORMED_REQUEST")
  void unknownStatusReturns400() throws Exception {
    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"LOST\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    verifyNoInteractions(statusService);
  }

  @Test
  @DisplayName("Parcel id that is not a UUID returns 400 INVALID_PARCEL_ID")
  void badUuidReturns400() throws Exception {
    send(as("dispatcher-1", "DISPATCHER"), "/api/v1/parcels/not-a-uuid/status", "{\"status\":\"PICKED_UP\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARCEL_ID"));
    verifyNoInteractions(statusService);
  }

  @Test
  @DisplayName("Unknown parcel returns 404 PARCEL_NOT_FOUND")
  void unknownParcelReturns404() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenThrow(new ParcelNotFoundException(PARCEL_ID));

    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"PICKED_UP\"}")
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("PARCEL_NOT_FOUND"));
  }

  @Test
  @DisplayName("Disallowed transition returns 409 INVALID_TRANSITION with current status and allowed next statuses")
  void invalidTransitionReturns409() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenThrow(InvalidTransitionException.notAllowed(ParcelStatus.PICKED_UP, ParcelStatus.DELIVERED));

    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"DELIVERED\"}")
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
        .andExpect(jsonPath("$.currentStatus").value("PICKED_UP"))
        .andExpect(jsonPath("$.requestedStatus").value("DELIVERED"))
        .andExpect(jsonPath("$.allowedNext", Matchers.containsInAnyOrder("IN_TRANSIT", "FAILED")));
  }

  @Test
  @DisplayName("Terminal parcel returns 409 PARCEL_TERMINAL with no allowed next statuses")
  void terminalReturns409() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenThrow(InvalidTransitionException.terminal(ParcelStatus.DELIVERED, ParcelStatus.FAILED));

    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"FAILED\",\"reason\":\"Lost\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PARCEL_TERMINAL"))
        .andExpect(jsonPath("$.allowedNext").isEmpty());
  }

  @Test
  @DisplayName("Missing status returns 422 with fieldErrors.status")
  void missingStatusReturns422() throws Exception {
    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"reason\":\"x\"}")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors.status").value("Status is required."));
    verifyNoInteractions(statusService);
  }

  @Test
  @DisplayName("Reason over 200 or note over 500 characters returns 422")
  void tooLongTextReturns422() throws Exception {
    send(as("dispatcher-1", "DISPATCHER"), URL,
        "{\"status\":\"FAILED\",\"reason\":\"" + "r".repeat(201) + "\",\"note\":\"" + "n".repeat(501) + "\"}")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors.reason").exists())
        .andExpect(jsonPath("$.fieldErrors.note").exists());
    verifyNoInteractions(statusService);
  }

  @Test
  @DisplayName("FAILED without a reason returns 422 with fieldErrors.reason")
  void failedWithoutReasonReturns422() throws Exception {
    when(statusService.updateStatus(eq(PARCEL_ID), eq(ParcelStatus.FAILED), isNull(), isNull(), any(), any()))
        .thenThrow(new StatusFieldException("reason", "A reason is required when the status is FAILED."));

    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"FAILED\"}")
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors.reason").value("A reason is required when the status is FAILED."));
  }

  @Test
  @DisplayName("Database failure returns 503 and does not leak the note")
  void databaseFailureReturns503() throws Exception {
    when(statusService.updateStatus(any(), any(), any(), any(), any(), any()))
        .thenThrow(new QueryTimeoutException("db down"));

    send(as("dispatcher-1", "DISPATCHER"), URL, "{\"status\":\"PICKED_UP\",\"note\":\"Call 0771234567\"}")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("STATUS_UPDATE_UNAVAILABLE"))
        .andExpect(content().string(Matchers.not(Matchers.containsString("0771234567"))));
  }
}
