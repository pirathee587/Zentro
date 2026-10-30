package com.zentro.identitypartner.courier;

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

import java.time.OffsetDateTime;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = AdminCourierController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class AdminCourierControllerTest {

  @Autowired private MockMvc mvc;
  @MockBean private CourierProfileService courierService;
  @MockBean private JwtDecoder jwtDecoder;

  private static final UUID COURIER_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Nested
  @DisplayName("Authentication & Authorization")
  class SecurityTests {

    @Test
    void unauthenticatedCallersReceive401() throws Exception {
      mvc.perform(get("/api/v1/admin/couriers"))
          .andExpect(status().isUnauthorized());

      mvc.perform(get("/api/v1/admin/couriers/" + COURIER_ID))
          .andExpect(status().isUnauthorized());

      mvc.perform(patch("/api/v1/admin/couriers/" + COURIER_ID)
          .contentType("application/json")
          .content("{\"active\":false}"))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(courierService);
    }

    @Test
    void customerCannotAccessAdminCouriers() throws Exception {
      mvc.perform(get("/api/v1/admin/couriers")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(courierService);
    }

    @Test
    void merchantCannotAccessAdminCouriers() throws Exception {
      mvc.perform(get("/api/v1/admin/couriers")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(courierService);
    }

    @Test
    void courierCannotAccessAdminCouriers() throws Exception {
      mvc.perform(get("/api/v1/admin/couriers")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
          .andExpect(status().isForbidden());

      mvc.perform(patch("/api/v1/admin/couriers/" + COURIER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER")))
          .contentType("application/json")
          .content("{\"active\":true}"))
          .andExpect(status().isForbidden());

      verifyNoInteractions(courierService);
    }

    @Test
    void dispatcherCanAccessAdminCouriers() throws Exception {
      when(courierService.listCouriers(any(), any(), any(), any())).thenReturn(List.of());

      mvc.perform(get("/api/v1/admin/couriers")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk());

      verify(courierService).listCouriers(any(), any(), any(), any());
    }
  }

  @Nested
  @DisplayName("Courier Profile Retrieval")
  class RetrievalTests {

    @Test
    void dispatcherCanGetCourierByIdWithSafeFields() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      when(courierService.getByCourierId(COURIER_ID)).thenReturn(
          new CourierProfile(COURIER_ID, USER_ID, "courier@zentro.lk", "Sunil Perera", "+94712345678", true, "ACTIVE", "Colombo Central", "MOTORCYCLE", now, now)
      );

      mvc.perform(get("/api/v1/admin/couriers/" + COURIER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(COURIER_ID.toString()))
          .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
          .andExpect(jsonPath("$.email").value("courier@zentro.lk"))
          .andExpect(jsonPath("$.name").value("Sunil Perera"))
          .andExpect(jsonPath("$.phone").value("+94712345678"))
          .andExpect(jsonPath("$.active").value(true))
          .andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
          .andExpect(jsonPath("$.assignedZone").value("Colombo Central"))
          .andExpect(jsonPath("$.vehicleType").value("MOTORCYCLE"))
          .andExpect(jsonPath("$.keycloak_subject").doesNotExist())
          .andExpect(jsonPath("$.password").doesNotExist());

      verify(courierService).getByCourierId(COURIER_ID);
    }

    @Test
    void returns404WhenCourierNotFound() throws Exception {
      when(courierService.getByCourierId(COURIER_ID))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier profile not found: " + COURIER_ID));

      mvc.perform(get("/api/v1/admin/couriers/" + COURIER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isNotFound());

      verify(courierService).getByCourierId(COURIER_ID);
    }

    @Test
    void dispatcherCanListCouriersWithFilters() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      when(courierService.listCouriers(eq("ACTIVE"), eq(true), eq("Colombo"), eq("Sunil"))).thenReturn(
          List.of(new CourierProfile(COURIER_ID, USER_ID, "sunil@zentro.lk", "Sunil Perera", "+94712345678", true, "ACTIVE", "Colombo", "MOTORCYCLE", now, now))
      );

      mvc.perform(get("/api/v1/admin/couriers")
          .param("status", "ACTIVE")
          .param("active", "true")
          .param("zone", "Colombo")
          .param("search", "Sunil")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].name").value("Sunil Perera"));

      verify(courierService).listCouriers("ACTIVE", true, "Colombo", "Sunil");
    }
  }

  @Nested
  @DisplayName("Courier Profile Updates")
  class UpdateTests {

    @Test
    void dispatcherCanUpdateCourierProfile() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      when(courierService.updateCourierProfile(eq(COURIER_ID), any(UpdateCourierProfileRequest.class))).thenReturn(
          new CourierProfile(COURIER_ID, USER_ID, "sunil@zentro.lk", "Sunil P.", "+94712345678", false, "ACTIVE", "Kandy", "VAN", now, now)
      );

      mvc.perform(patch("/api/v1/admin/couriers/" + COURIER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"active\":false,\"assignedZone\":\"Kandy\",\"vehicleType\":\"VAN\",\"name\":\"Sunil P.\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.active").value(false))
          .andExpect(jsonPath("$.assignedZone").value("Kandy"))
          .andExpect(jsonPath("$.vehicleType").value("VAN"))
          .andExpect(jsonPath("$.name").value("Sunil P."));

      verify(courierService).updateCourierProfile(eq(COURIER_ID), any(UpdateCourierProfileRequest.class));
    }

    @Test
    void updateReturns404WhenCourierProfileDoesNotExist() throws Exception {
      when(courierService.updateCourierProfile(eq(COURIER_ID), any(UpdateCourierProfileRequest.class)))
          .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier profile not found: " + COURIER_ID));

      mvc.perform(patch("/api/v1/admin/couriers/" + COURIER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"active\":true}"))
          .andExpect(status().isNotFound());

      verify(courierService).updateCourierProfile(eq(COURIER_ID), any(UpdateCourierProfileRequest.class));
    }

    @Test
    void updateFailsValidationForInvalidVehicleType() throws Exception {
      mvc.perform(patch("/api/v1/admin/couriers/" + COURIER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content("{\"vehicleType\":\"AIRPLANE\"}"))
          .andExpect(status().isBadRequest());

      verifyNoInteractions(courierService);
    }

    @Test
    void updateFailsValidationWhenFieldLengthsExceeded() throws Exception {
      String tooLongName = "A".repeat(201);
      String tooLongPhone = "0".repeat(33);
      String tooLongZone = "Z".repeat(65);

      mvc.perform(patch("/api/v1/admin/couriers/" + COURIER_ID)
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER")))
          .contentType("application/json")
          .content(String.format("{\"name\":\"%s\",\"phone\":\"%s\",\"assignedZone\":\"%s\"}", tooLongName, tooLongPhone, tooLongZone)))
          .andExpect(status().isBadRequest());

      verifyNoInteractions(courierService);
    }
  }
}
