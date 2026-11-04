package com.zentro.identitypartner.courier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = CourierSelfProfileController.class)
@Import(com.zentro.security.config.ZentroSecurityAutoConfiguration.class)
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class CourierSelfProfileControllerTest {

  @Autowired private MockMvc mvc;
  @MockBean private CourierProfileService courierService;
  @MockBean private JwtDecoder jwtDecoder;

  private static final UUID PROFILE_ID = UUID.randomUUID();
  private static final UUID USER_ID = UUID.randomUUID();

  @Nested
  @DisplayName("Authentication & Role Authorization")
  class SecurityTests {

    @Test
    void unauthenticatedCallersReceive401() throws Exception {
      mvc.perform(get("/api/v1/courier/profile"))
          .andExpect(status().isUnauthorized());

      mvc.perform(post("/api/v1/courier/profile/provision"))
          .andExpect(status().isUnauthorized());

      verifyNoInteractions(courierService);
    }

    @Test
    void customerCannotAccessCourierProfile() throws Exception {
      mvc.perform(get("/api/v1/courier/profile")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isForbidden());

      mvc.perform(post("/api/v1/courier/profile/provision")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(courierService);
    }

    @Test
    void merchantCannotAccessCourierProfile() throws Exception {
      mvc.perform(get("/api/v1/courier/profile")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isForbidden());

      mvc.perform(post("/api/v1/courier/profile/provision")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(courierService);
    }

    @Test
    void dispatcherWithoutCourierRoleCannotAccessCourierSelfProfile() throws Exception {
      mvc.perform(get("/api/v1/courier/profile")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
          .andExpect(status().isForbidden());

      verifyNoInteractions(courierService);
    }
  }

  @Nested
  @DisplayName("Courier Profile Self-Service & Provisioning")
  class SelfServiceTests {

    @Test
    void courierCanGetOwnProfile() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      when(courierService.getByJwt(any(Jwt.class))).thenReturn(
          new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal P", "+94771234567", true, "ACTIVE", "Colombo", "MOTORCYCLE", now, now)
      );

      mvc.perform(get("/api/v1/courier/profile")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(PROFILE_ID.toString()))
          .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
          .andExpect(jsonPath("$.email").value("courier@zentro.lk"))
          .andExpect(jsonPath("$.name").value("Kamal P"))
          .andExpect(jsonPath("$.phone").value("+94771234567"))
          .andExpect(jsonPath("$.active").value(true))
          .andExpect(jsonPath("$.assignedZone").value("Colombo"))
          .andExpect(jsonPath("$.vehicleType").value("MOTORCYCLE"));

      verify(courierService).getByJwt(any(Jwt.class));
    }

    @Test
    void courierCanAccessViaGatewayCompatibleUsersRoute() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      when(courierService.getByJwt(any(Jwt.class))).thenReturn(
          new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal P", "+94771234567", true, "ACTIVE", "Colombo", "MOTORCYCLE", now, now)
      );

      mvc.perform(get("/api/v1/users/courier-profile")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(PROFILE_ID.toString()));

      verify(courierService).getByJwt(any(Jwt.class));
    }

    @Test
    void courierCanExplicitlyProvisionProfile() throws Exception {
      OffsetDateTime now = OffsetDateTime.now();
      when(courierService.provisionCourier(any(Jwt.class))).thenReturn(
          new CourierProfile(PROFILE_ID, USER_ID, "courier@zentro.lk", "Kamal P", "+94771234567", true, "ACTIVE", null, null, now, now)
      );

      mvc.perform(post("/api/v1/courier/profile/provision")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(PROFILE_ID.toString()))
          .andExpect(jsonPath("$.active").value(true));

      verify(courierService).provisionCourier(any(Jwt.class));
    }

    @Test
    void returns403WhenServiceDeniesNonCourierJwtClaim() throws Exception {
      when(courierService.getByJwt(any(Jwt.class)))
          .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Authenticated user does not have COURIER role"));

      mvc.perform(get("/api/v1/courier/profile")
          .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
          .andExpect(status().isForbidden());
    }
  }
}
