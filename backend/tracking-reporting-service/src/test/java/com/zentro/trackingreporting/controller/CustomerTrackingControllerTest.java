package com.zentro.trackingreporting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.trackingreporting.config.TrackingSecurityConfig;
import com.zentro.trackingreporting.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.dto.CustomerDeliveryResponse;
import com.zentro.trackingreporting.dto.ParcelDetailsDto;
import com.zentro.trackingreporting.dto.PartyDto;
import com.zentro.trackingreporting.dto.TrackingMilestoneDto;
import com.zentro.trackingreporting.error.GlobalTrackingExceptionHandler;
import com.zentro.trackingreporting.service.TrackingService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = CustomerTrackingController.class)
@Import({TrackingSecurityConfig.class, GlobalTrackingExceptionHandler.class})
class CustomerTrackingControllerTest {

  @Autowired private MockMvc mockMvc;
  @MockBean private TrackingService trackingService;
  @MockBean private JwtDecoder jwtDecoder;

  @Test
  @DisplayName("GET /api/v1/tracking/mine rejects unauthenticated request with 401")
  void getMyDeliveriesUnauthenticated() throws Exception {
    mockMvc.perform(get("/api/v1/tracking/mine")
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("GET /api/v1/tracking/mine rejects non-customer/non-merchant role with 403")
  void getMyDeliveriesForbiddenRole() throws Exception {
    mockMvc.perform(get("/api/v1/tracking/mine")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_COURIER")))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("GET /api/v1/tracking/mine allows customer and isolates deliveries to token subject")
  void getMyDeliveriesCustomerSuccess() throws Exception {
    String customerSub = "auth0|customer-john";
    CustomerDeliveryResponse delivery = new CustomerDeliveryResponse(
        "del-1",
        "ZT-CUSTOMER11",
        "In progress",
        "IN_TRANSIT",
        new PartyDto("Sender", "0771234567", "12 Main St", "Colombo"),
        new PartyDto("Recipient", "0779876543", "45 Park St", "Kandy"),
        new ParcelDetailsDto("Documents", BigDecimal.ONE, "Documents", "SMALL"),
        new BigDecimal("650.00"),
        "LKR",
        "LKR 650.00",
        2,
        Instant.parse("2026-11-04T10:00:00Z"),
        "4 Nov 2026",
        null,
        null,
        null,
        List.of(new TrackingMilestoneDto("m1", "Created", "Booked", Instant.now(), "completed", "Colombo", null))
    );

    CustomerDeliveryListResponse mockList = new CustomerDeliveryListResponse(
        List.of(delivery),
        List.of(delivery),
        1L,
        1L,
        1,
        1,
        10,
        new BigDecimal("650.00"),
        "LKR 650.00"
    );

    when(trackingService.getCustomerDeliveries(eq(customerSub), eq(1), eq(10), eq(null), eq(null)))
        .thenReturn(mockList);

    mockMvc.perform(get("/api/v1/tracking/mine")
            .with(jwt().jwt(j -> j.subject(customerSub)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.content[0].trackingRef").value("ZT-CUSTOMER11"))
        .andExpect(jsonPath("$.parcels[0].trackingRef").value("ZT-CUSTOMER11"))
        .andExpect(jsonPath("$.totalSpent").value(650.00))
        .andExpect(jsonPath("$.content[0].sender.name").value("Sender"))
        .andExpect(jsonPath("$.content[0].recipient.name").value("Recipient"));

    // Verify customer identity is extracted strictly from JWT token subject
    verify(trackingService).getCustomerDeliveries(customerSub, 1, 10, null, null);
  }

  @Test
  @DisplayName("GET /api/v1/tracking/mine supports merchant role")
  void getMyDeliveriesMerchantSuccess() throws Exception {
    String merchantSub = "merchant-corp-456";
    CustomerDeliveryListResponse emptyList = new CustomerDeliveryListResponse(
        List.of(), List.of(), 0L, 0L, 0, 1, 10, BigDecimal.ZERO, "LKR 0"
    );

    when(trackingService.getCustomerDeliveries(eq(merchantSub), anyInt(), anyInt(), any(), any()))
        .thenReturn(emptyList);

    mockMvc.perform(get("/api/v1/tracking/mine")
            .with(jwt().jwt(j -> j.subject(merchantSub)).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
            .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
  }
}
