package com.zentro.parcelpricing.parcel.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.parcelpricing.exception.BookingExceptionHandler;
import com.zentro.parcelpricing.exception.MerchantOrderExceptionHandler;
import com.zentro.parcelpricing.integration.IdentityMerchantClient;
import com.zentro.parcelpricing.parcel.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.service.MerchantOrderService;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService.BookingResult;
import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Characterization test suite for ZD-353 (#353) controller findings:
 * - X01 (#191): Location header contract and absent GET /api/v1/parcels/{id} endpoint in S2.
 * - G43 (#257): Merchant order list limit clamping [1, 100] and lack of cursor pagination.
 */
@WebMvcTest(controllers = {ParcelBookingController.class, MerchantOrderController.class})
@Import({
    ZentroSecurityAutoConfiguration.class,
    BookingExceptionHandler.class,
    MerchantOrderExceptionHandler.class
})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class Zd353ControllerCharacterizationTest {

  @Autowired MockMvc mvc;
  @MockBean ParcelBookingService bookingService;
  @MockBean MerchantOrderService orderService;
  @MockBean IdentityMerchantClient identityClient;
  @MockBean JwtDecoder jwtDecoder;

  @Test
  @DisplayName("X01: POST /api/v1/parcels returns Location header pointing to unmapped GET endpoint")
  void x01_bookingReturnsLocationHeaderPointingToUnmappedGetEndpoint() throws Exception {
    UUID parcelId = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
    ParcelBookingResponse mockResponse = new ParcelBookingResponse(
        parcelId,
        "ZT-CHARACT003",
        ParcelStatus.CREATED,
        new BigDecimal("550.00"),
        "LKR",
        2,
        OffsetDateTime.now(),
        new ParcelBookingResponse.Contact("Alice", "0771234567", "12 Galle Rd, Colombo 03", "Colombo"),
        new ParcelBookingResponse.Contact("Bob", "0771234568", "14 Galle Rd, Colombo 03", "Colombo"),
        new ParcelBookingResponse.Parcel(new BigDecimal("1.00"), "Box", ParcelSize.SMALL, null));

    when(bookingService.book(any(), any(), any()))
        .thenReturn(new BookingResult(mockResponse, true));

    String validBookingPayload = """
        {
          "sender": {"name": "Alice", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
          "recipient": {"name": "Bob", "phone": "0771234568", "address": "14 Galle Road, Colombo 03"},
          "parcel": {"weightKg": 1.0, "category": "Box", "size": "SMALL"},
          "destinationType": "SAME_CITY"
        }
        """;

    // 1. POST /api/v1/parcels returns 201 with Location header
    mvc.perform(post("/api/v1/parcels")
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBookingPayload))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/parcels/" + parcelId));

    // 2. Following the Location header with GET /api/v1/parcels/{id} fails with 404 Not Found
    // because S2 does not implement an authoritative parcel retrieval GET endpoint
    mvc.perform(get("/api/v1/parcels/" + parcelId)
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isNotFound());
  }

  @ParameterizedTest(name = "limit={0} -> clamped limit={1}")
  @CsvSource({
      "-10, 1",
      "0, 1",
      "1, 1",
      "50, 50",
      "100, 100",
      "101, 100",
      "500, 100"
  })
  @DisplayName("G43: GET /api/v1/merchant/orders clamps limit query parameter to [1, 100]")
  void g43_merchantOrderListClampsLimit(int requestLimit, int expectedClampedLimit) throws Exception {
    String merchantId = "merchant-zd353";
    when(orderService.listByMerchant(eq(merchantId), anyInt())).thenReturn(List.of());

    mvc.perform(get("/api/v1/merchant/orders")
            .param("limit", String.valueOf(requestLimit))
            .with(jwt().jwt(j -> j.subject(merchantId).tokenValue("tok-353"))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
        .andExpect(status().isOk());

    verify(orderService).listByMerchant(eq(merchantId), eq(expectedClampedLimit));
  }

  @Test
  @DisplayName("G43: Default limit for GET /api/v1/merchant/orders is 50")
  void g43_defaultLimitIsFifty() throws Exception {
    String merchantId = "merchant-zd353";
    when(orderService.listByMerchant(eq(merchantId), anyInt())).thenReturn(List.of());

    mvc.perform(get("/api/v1/merchant/orders")
            .with(jwt().jwt(j -> j.subject(merchantId).tokenValue("tok-353"))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
        .andExpect(status().isOk());

    verify(orderService).listByMerchant(eq(merchantId), eq(50));
  }
}
