package com.zentro.parcelpricing.parcel.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.parcelpricing.exception.BookingExceptionHandler;
import com.zentro.parcelpricing.exception.ParcelNotFoundException;
import com.zentro.parcelpricing.parcel.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService.BookingResult;
import com.zentro.security.config.ZentroSecurityAutoConfiguration;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Controller tests for X01: GET /api/v1/parcels/{id} — authoritative parcel retrieval.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Owner customer retrieves their own parcel (200 OK).</li>
 *   <li>POST /api/v1/parcels returns a Location that resolves to 200 for the same customer.</li>
 *   <li>Idempotent booking replay still returns a usable id via GET.</li>
 *   <li>CUSTOMER cannot retrieve a foreign parcel (404 — prevents enumeration).</li>
 *   <li>DISPATCHER can retrieve any parcel (200 OK).</li>
 *   <li>SERVICE can retrieve any parcel (200 OK).</li>
 *   <li>Unknown parcel id returns 404.</li>
 *   <li>Wrong role (MERCHANT, unauthenticated) returns 403 / 401.</li>
 *   <li>Malformed UUID path variable returns 400.</li>
 * </ul>
 */
@WebMvcTest(controllers = ParcelBookingController.class)
@Import({ZentroSecurityAutoConfiguration.class, BookingExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class ParcelRetrievalControllerTest {

  private static final UUID PARCEL_ID = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
  private static final String CUSTOMER_SUBJECT = "customer-owner-1";
  private static final String FOREIGN_SUBJECT   = "customer-foreign-99";
  private static final String BASE_URL = "/api/v1/parcels";
  private static final String GET_URL  = BASE_URL + "/" + PARCEL_ID;

  @Autowired MockMvc mvc;
  @MockBean ParcelBookingService bookingService;
  @MockBean JwtDecoder jwtDecoder;

  // ─── X01 ────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("X01: CUSTOMER owner retrieves their parcel — 200 OK with correct body")
  void x01_ownerCustomerRetrievesParcel() throws Exception {
    when(bookingService.getByIdAndOwner(eq(PARCEL_ID), eq(CUSTOMER_SUBJECT))).thenReturn(sampleResponse());

    mvc.perform(get(GET_URL).with(customer(CUSTOMER_SUBJECT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PARCEL_ID.toString()))
        .andExpect(jsonPath("$.trackingRef").value("ZT-X01TEST001"))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.fee").value(550.00))
        .andExpect(jsonPath("$.currency").value("LKR"));

    verify(bookingService).getByIdAndOwner(eq(PARCEL_ID), eq(CUSTOMER_SUBJECT));
  }

  @Test
  @DisplayName("X01: POST returns Location header whose GET resolves to 200 for the same CUSTOMER")
  void x01_postLocationHeaderResolvesTo200ForOwner() throws Exception {
    ParcelBookingResponse created = sampleResponse();
    when(bookingService.book(eq(CUSTOMER_SUBJECT), any(), any()))
        .thenReturn(new BookingResult(created, true));
    when(bookingService.getByIdAndOwner(eq(PARCEL_ID), eq(CUSTOMER_SUBJECT)))
        .thenReturn(created);

    // 1. POST → 201 with Location
    mvc.perform(post(BASE_URL)
            .with(customer(CUSTOMER_SUBJECT))
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBookingBody()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", BASE_URL + "/" + PARCEL_ID));

    // 2. Follow Location → 200
    mvc.perform(get(BASE_URL + "/" + PARCEL_ID).with(customer(CUSTOMER_SUBJECT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PARCEL_ID.toString()));
  }

  @Test
  @DisplayName("X01: Idempotent booking replay (200) — subsequent GET returns same resource")
  void x01_idempotentReplayFollowedByGet() throws Exception {
    ParcelBookingResponse existing = sampleResponse();
    when(bookingService.book(eq(CUSTOMER_SUBJECT), any(), any()))
        .thenReturn(new BookingResult(existing, false)); // replay
    when(bookingService.getByIdAndOwner(eq(PARCEL_ID), eq(CUSTOMER_SUBJECT)))
        .thenReturn(existing);

    // Replayed booking returns 200, no Location header
    mvc.perform(post(BASE_URL)
            .with(customer(CUSTOMER_SUBJECT))
            .contentType(MediaType.APPLICATION_JSON)
            .content(validBookingBody()))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"));

    // GET still works for the same owner
    mvc.perform(get(BASE_URL + "/" + PARCEL_ID).with(customer(CUSTOMER_SUBJECT)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PARCEL_ID.toString()));
  }

  @Test
  @DisplayName("X01: CUSTOMER retrieving a foreign parcel gets 404 (prevents enumeration)")
  void x01_foreignCustomerGets404() throws Exception {
    when(bookingService.getByIdAndOwner(eq(PARCEL_ID), eq(FOREIGN_SUBJECT)))
        .thenThrow(new ParcelNotFoundException(PARCEL_ID));

    mvc.perform(get(GET_URL).with(customer(FOREIGN_SUBJECT)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Parcel not found"));

    verify(bookingService).getByIdAndOwner(eq(PARCEL_ID), eq(FOREIGN_SUBJECT));
  }

  @Test
  @DisplayName("X01: DISPATCHER retrieves any parcel — 200 OK without ownership scope")
  void x01_dispatcherRetrievesAnyParcel() throws Exception {
    when(bookingService.getById(eq(PARCEL_ID))).thenReturn(sampleResponse());

    mvc.perform(get(GET_URL).with(dispatcher()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PARCEL_ID.toString()));

    verify(bookingService).getById(eq(PARCEL_ID));
  }

  @Test
  @DisplayName("X01: SERVICE role retrieves any parcel — 200 OK without ownership scope")
  void x01_serviceRoleRetrievesAnyParcel() throws Exception {
    when(bookingService.getById(eq(PARCEL_ID))).thenReturn(sampleResponse());

    mvc.perform(get(GET_URL).with(service()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(PARCEL_ID.toString()));

    verify(bookingService).getById(eq(PARCEL_ID));
  }

  @Test
  @DisplayName("X01: Unknown parcel id returns 404 for CUSTOMER owner")
  void x01_unknownParcelIdReturns404() throws Exception {
    UUID unknown = UUID.fromString("00000000-0000-4000-8000-000000000099");
    when(bookingService.getByIdAndOwner(eq(unknown), eq(CUSTOMER_SUBJECT)))
        .thenThrow(new ParcelNotFoundException(unknown));

    mvc.perform(get(BASE_URL + "/" + unknown).with(customer(CUSTOMER_SUBJECT)))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("X01: Unknown parcel id returns 404 for DISPATCHER")
  void x01_unknownParcelIdReturns404ForDispatcher() throws Exception {
    UUID unknown = UUID.fromString("00000000-0000-4000-8000-000000000099");
    when(bookingService.getById(eq(unknown)))
        .thenThrow(new ParcelNotFoundException(unknown));

    mvc.perform(get(BASE_URL + "/" + unknown).with(dispatcher()))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("X01: MERCHANT role cannot access parcel retrieval endpoint — 403 Forbidden")
  void x01_merchantRoleIsForbidden() throws Exception {
    mvc.perform(get(GET_URL)
            .with(jwt().jwt(j -> j.subject("merchant-1"))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
        .andExpect(status().isForbidden());

    verifyNoInteractions(bookingService);
  }

  @Test
  @DisplayName("X01: Unauthenticated request returns 401 Unauthorized")
  void x01_unauthenticatedRequestIs401() throws Exception {
    mvc.perform(get(GET_URL)).andExpect(status().isUnauthorized());

    verifyNoInteractions(bookingService);
  }

  @Test
  @DisplayName("X01: Malformed UUID path variable returns 400 Bad Request")
  void x01_malformedUuidReturns400() throws Exception {
    mvc.perform(get(BASE_URL + "/not-a-uuid").with(customer(CUSTOMER_SUBJECT)))
        .andExpect(status().isBadRequest());
  }

  // ─── Helpers ────────────────────────────────────────────────────────────────

  private static ParcelBookingResponse sampleResponse() {
    return new ParcelBookingResponse(
        PARCEL_ID, "ZT-X01TEST001", ParcelStatus.CREATED,
        new BigDecimal("550.00"), "LKR", 2, OffsetDateTime.now(),
        new ParcelBookingResponse.Contact("Alice", "0771234567", "12 Galle Rd, Colombo 03", "Colombo"),
        new ParcelBookingResponse.Contact("Bob", "0771234568", "14 Galle Rd, Colombo 03", "Colombo"),
        new ParcelBookingResponse.Parcel(new BigDecimal("1.00"), "Box", ParcelSize.SMALL, null));
  }

  private static String validBookingBody() {
    return """
        {
          "sender": {"name": "Alice", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
          "recipient": {"name": "Bob", "phone": "0771234568", "address": "14 Galle Road, Colombo 03"},
          "parcel": {"weightKg": 1.0, "category": "Box", "size": "SMALL"},
          "destinationType": "SAME_CITY"
        }
        """;
  }

  private static RequestPostProcessor customer(String subject) {
    return jwt().jwt(j -> j.subject(subject))
        .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }

  private static RequestPostProcessor dispatcher() {
    return jwt().jwt(j -> j.subject("dispatcher-1"))
        .authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"));
  }

  private static RequestPostProcessor service() {
    return jwt().jwt(j -> j.subject("service-s3"))
        .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
  }
}

