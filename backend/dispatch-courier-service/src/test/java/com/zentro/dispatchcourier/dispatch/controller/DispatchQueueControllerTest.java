package com.zentro.dispatchcourier.dispatch.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.dispatch.service.DispatchService;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
import com.zentro.dispatchcourier.exception.DispatchException;
import com.zentro.dispatchcourier.exception.DispatchExceptionHandler;
import com.zentro.dispatchcourier.security.DispatchCourierSecurityConfig;
import org.junit.jupiter.api.BeforeEach;
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

@WebMvcTest(DispatchQueueController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class,
    DispatchCourierSecurityConfig.class, com.zentro.dispatchcourier.security.DispatchPermissions.class, DispatchService.class,
    DispatchExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class DispatchQueueControllerTest {
  @Autowired MockMvc mvc;
  @MockBean JwtDecoder decoder;
  @MockBean ParcelSource source;
  @MockBean AssignmentRepository assignments;
  @MockBean IdentityCourierClient identity;
  static final String PARCEL = "11111111-1111-4111-8111-111111111111";
  static final String COURIER = "22222222-2222-4222-8222-222222222222";
  static final String BODY = "{\"parcelId\":\"" + PARCEL + "\",\"courierId\":\"" + COURIER + "\"}";

  @ParameterizedTest
  @ValueSource(strings = {"queue", "unassigned", "workload", "eligible-couriers"})
  void everyReadRequiresViewRatherThanAssign(String path) throws Exception {
    for (var permissions : java.util.List.of(java.util.List.<String>of(), java.util.List.of("DISPATCH_ASSIGN"))) {
      mvc.perform(get("/api/v1/dispatch/" + path).param("parcelId", PARCEL)
          .with(jwt().jwt(j -> j.claim("permissions", permissions))
              .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(source, assignments, identity);
  }

  @Test void viewOnlyCannotAssignEvenWithForgedPermissionHeaderAndBody() throws Exception {
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json")
        .header("X-Permissions", "DISPATCH_ASSIGN")
        .content(BODY.replace("}", ",\"permissions\":[\"DISPATCH_ASSIGN\"]}"))
        .with(jwt().jwt(j -> j.claim("permissions", java.util.List.of("DISPATCH_VIEW")))
            .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(source, assignments, identity);
  }

  @ParameterizedTest
  @ValueSource(strings = {"queue", "unassigned", "workload", "eligible-couriers"})
  void viewPermissionAllowsEachReadHandler(String path) throws Exception {
    doReturn(new ParcelSource.Snapshot(java.util.List.of(), true)).when(source).eligibleParcels(anyString());
    doAnswer(call -> {
      java.util.function.Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      return action.apply(new ParcelSource.Parcel(java.util.UUID.fromString(PARCEL), "TEST", "Pickup", "Dropoff", java.time.Instant.now()));
    }).when(source).withEligibleParcel(any(), anyString(), any());
    mvc.perform(get("/api/v1/dispatch/" + path).param("parcelId", PARCEL)
        .with(jwt().jwt(j -> j.claim("permissions", java.util.List.of("DISPATCH_VIEW")))
            .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk());
  }

  @BeforeEach void setup() {
    when(source.eligibleParcels(anyString())).thenThrow(new DispatchException(
        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "DISPATCH_SOURCE_UNAVAILABLE", "Unavailable"));
    doThrow(new DispatchException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
        "DISPATCH_SOURCE_UNAVAILABLE", "Unavailable"))
        .when(source).withEligibleParcel(any(), anyString(), any());
  }

  @Test void anonymousCannotAccessQueue() throws Exception {
    mvc.perform(get("/api/v1/dispatch/queue")).andExpect(status().isUnauthorized());
  }

  @Test void anonymousCannotAccessWorkload() throws Exception {
    mvc.perform(get("/api/v1/dispatch/workload")).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER"})
  void otherRolesCannotAccessQueue(String role) throws Exception {
    mvc.perform(get("/api/v1/dispatch/queue").with(jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/dispatch/workload").with(jwt()
        .authorities(new SimpleGrantedAuthority("ROLE_" + role))))
        .andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json").content(BODY)
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/dispatch/eligible-couriers").param("parcelId", PARCEL)
        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_" + role))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(assignments, identity, source);
  }

  @Test void dispatcherCanReadWorkloadWithAssignmentCounts() throws Exception {
    var courier = new IdentityCourierClient.Courier(java.util.UUID.fromString(COURIER),
        java.util.UUID.randomUUID(), "Courier", true, "ACTIVE");
    when(assignments.assignmentCounts()).thenReturn(java.util.Map.of(courier.id(), 2L));
    when(identity.listAll(anyString())).thenReturn(java.util.List.of(courier));

    mvc.perform(get("/api/v1/dispatch/workload").with(jwt()
        .jwt(token -> token.claim("permissions", java.util.List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN")))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].courierId").value(COURIER))
        .andExpect(jsonPath("$.items[0].assignmentCount").value(2))
        .andExpect(jsonPath("$.items[0].availability").value("AVAILABLE"))
        .andExpect(jsonPath("$.overloadConfigured").value(false));
  }

  @Test void dispatcherGetsExplicitUnavailabilityWithoutFabricatedTotal() throws Exception {
    mvc.perform(get("/api/v1/dispatch/queue").with(jwt()
        .jwt(token -> token.claim("permissions", java.util.List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN")))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.code").value("DISPATCH_SOURCE_UNAVAILABLE"))
        .andExpect(jsonPath("$.total").doesNotExist());
  }

  @Test void anonymousCannotAssign() throws Exception {
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json").content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test void unavailableSourceNeverCreatesAssignment() throws Exception {
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json").content(BODY)
        .with(jwt().jwt(token -> token.claim("permissions", java.util.List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN")))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DISPATCH_SOURCE_UNAVAILABLE"));
    verify(assignments, never()).insert(any(), any(), any());
    verifyNoInteractions(identity);
  }

  @ParameterizedTest @ValueSource(strings = {"{}", "{\"parcelId\":\"bad\",\"courierId\":null}", "null", "not-json"})
  void invalidAssignmentReturnsProblemDetails(String body) throws Exception {
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json").content(body)
        .with(jwt().jwt(token -> token.claim("permissions", java.util.List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN")))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    verifyNoInteractions(source, assignments, identity);
  }

  @Test void conflictIsExplicitAndDoesNotCallSource() throws Exception {
    when(assignments.exists(java.util.UUID.fromString(PARCEL))).thenReturn(true);
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json").content(BODY)
        .with(jwt().jwt(token -> token.claim("permissions", java.util.List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN")))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DELIVERY_ALREADY_ASSIGNED"));
    verifyNoInteractions(source, identity);
  }

  @Test void storageFailureDoesNotLeakDatabaseDetails() throws Exception {
    when(assignments.exists(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("secret database host"));
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json").content(BODY)
        .with(jwt().jwt(token -> token.claim("permissions", java.util.List.of("DISPATCH_VIEW", "DISPATCH_ASSIGN")))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DISPATCH_STORAGE_UNAVAILABLE"))
        .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret"))));
  }

  @Test void validAssignmentUsesTokenSubjectAndCanonicalCourier() throws Exception {
    var parcelId = java.util.UUID.fromString(PARCEL); var courierId = java.util.UUID.fromString(COURIER);
    var parcel = new ParcelSource.Parcel(parcelId, "TEST", "Pickup", "Dropoff", java.time.Instant.now());
    doAnswer(call -> {
      java.util.function.Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      return action.apply(parcel);
    }).when(source).withEligibleParcel(any(), anyString(), any());
    var courier = new IdentityCourierClient.Courier(courierId, java.util.UUID.randomUUID(), "Test", true, "ACTIVE");
    when(identity.requireEligible(eq(courierId), anyString())).thenReturn(courier);
    when(assignments.insert(parcelId, courier, "dispatcher-subject")).thenReturn(
        new AssignmentRepository.Assignment(parcelId, courierId, courier.userId(), "dispatcher-subject", java.time.Instant.now()));
    mvc.perform(post("/api/v1/dispatch/assignments").contentType("application/json").content(BODY)
        .with(jwt().jwt(token -> token.subject("dispatcher-subject")
            .claim("permissions", java.util.List.of("DISPATCH_ASSIGN")))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.courierId").value(COURIER))
        .andExpect(jsonPath("$.assignedBy").value("dispatcher-subject"));
  }
}
