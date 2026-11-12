package com.zentro.parcelpricing.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zentro.parcelpricing.exception.BookingExceptionHandler;
import com.zentro.parcelpricing.exception.IdempotencyConflictException;
import com.zentro.parcelpricing.exception.GlobalPricingExceptionHandler;
import com.zentro.parcelpricing.exception.MerchantOrderExceptionHandler;
import com.zentro.parcelpricing.parcel.controller.MerchantOrderController;
import com.zentro.parcelpricing.parcel.controller.ParcelBookingController;
import com.zentro.parcelpricing.parcel.dto.CreateMerchantOrderRequest;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.parcel.dto.MerchantOrderResponse;
import com.zentro.parcelpricing.parcel.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.service.MerchantOrderService;
import com.zentro.parcelpricing.parcel.service.MerchantOrderService.OrderResult;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService.BookingResult;
import com.zentro.parcelpricing.pricing.controller.AdminPricingRuleController;
import com.zentro.parcelpricing.pricing.controller.ParcelPricingController;
import com.zentro.parcelpricing.pricing.controller.ZoneController;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.pricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.pricing.dto.PricingRuleUpdateRequest;
import com.zentro.parcelpricing.pricing.rules.DeliveryZone;
import com.zentro.parcelpricing.pricing.rules.PricingRule;
import com.zentro.parcelpricing.pricing.service.PricingService;
import com.zentro.parcelpricing.pricing.service.ZoneService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Executable boundary contract test for S2 Booking and Pricing APIs (ZD-075).
 * Validates role authorization, input validation negative cases (422/400 Problem Details),
 * idempotency replays (200 vs 201 + Location vs 409 conflict), and OpenAPI schema compliance.
 */
@WebMvcTest(controllers = {
    ParcelBookingController.class,
    ParcelPricingController.class,
    ZoneController.class,
    AdminPricingRuleController.class,
    MerchantOrderController.class
})
@Import({
    com.zentro.security.config.ZentroSecurityAutoConfiguration.class,
    BookingExceptionHandler.class,
    GlobalPricingExceptionHandler.class,
    MerchantOrderExceptionHandler.class
})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class BookingBoundaryContractTest {

  private static final UUID PARCEL_ID = UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c");
  private static final String TRACKING_REF = "ZT-ABCDEFGH12";
  private static final OffsetDateTime CREATED_AT = OffsetDateTime.of(2026, 11, 10, 10, 0, 0, 0, ZoneOffset.UTC);

  private static final String VALID_BOOKING_JSON = """
      {
        "sender": {"name": "Sender Contact", "phone": "0771234567", "address": "12 Galle Road, Colombo 03", "city": "Colombo"},
        "recipient": {"name": "Kamala Silva", "phone": "+94712345678", "address": "45 Temple Street, Kandy", "city": "Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Small Box", "size": "SMALL"},
        "destinationType": "OTHER_DISTRICT"
      }
      """;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @MockBean ParcelBookingService bookingService;
  @MockBean PricingService pricingService;
  @MockBean ZoneService zoneService;
  @MockBean MerchantOrderService merchantOrderService;
  @MockBean com.zentro.parcelpricing.integration.IdentityMerchantClient identityMerchantClient;
  @MockBean JwtDecoder jwtDecoder;

  private ParcelBookingResponse sampleBookingResponse() {
    return new ParcelBookingResponse(PARCEL_ID, TRACKING_REF, ParcelStatus.CREATED, new BigDecimal("1072.00"), "LKR", 3,
        CREATED_AT,
        new ParcelBookingResponse.Contact("Sender Contact", "0771234567", "12 Galle Road, Colombo 03", "Colombo"),
        new ParcelBookingResponse.Contact("Kamala Silva", "+94712345678", "45 Temple Street, Kandy", "Kandy"),
        new ParcelBookingResponse.Parcel(new BigDecimal("2.0"), "Small Box", ParcelSize.SMALL, null));
  }

  // --- 1. Booking Boundary Contracts ---

  @Test
  @DisplayName("POST /api/v1/parcels - New booking returns 201 with Location header and matches OpenAPI schema")
  void newBookingReturns201AndConformsToSchema() throws Exception {
    when(bookingService.book(eq("customer-1"), any(), eq("idem-key-1")))
        .thenReturn(new BookingResult(sampleBookingResponse(), true));

    MvcResult result = mvc.perform(post("/api/v1/parcels")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "idem-key-1")
            .content(VALID_BOOKING_JSON)
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/parcels/" + PARCEL_ID))
        .andReturn();

    OpenApiResponseAssertions.assertMatches("/api/v1/parcels", "post", result);
  }

  @Test
  @DisplayName("POST /api/v1/parcels - Idempotent replay returns 200 without Location header and matches OpenAPI schema")
  void idempotentBookingReplayReturns200AndConformsToSchema() throws Exception {
    when(bookingService.book(eq("customer-1"), any(), eq("idem-key-1")))
        .thenReturn(new BookingResult(sampleBookingResponse(), false));

    MvcResult result = mvc.perform(post("/api/v1/parcels")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "idem-key-1")
            .content(VALID_BOOKING_JSON)
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"))
        .andReturn();

    OpenApiResponseAssertions.assertMatches("/api/v1/parcels", "post", result);
  }

  @Test
  @DisplayName("POST /api/v1/parcels - Conflicting idempotency payload returns 409 Conflict ProblemDetail")
  void conflictingBookingIdempotencyReturns409() throws Exception {
    when(bookingService.book(eq("customer-1"), any(), eq("idem-key-1")))
        .thenThrow(new IdempotencyConflictException());

    MvcResult result = mvc.perform(post("/api/v1/parcels")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "idem-key-1")
            .content(VALID_BOOKING_JSON)
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Idempotency conflict"))
        .andExpect(jsonPath("$.status").value(409))
        .andReturn();

    OpenApiResponseAssertions.assertMatches("/api/v1/parcels", "post", result);
  }

  @Test
  @DisplayName("POST /api/v1/parcels - Validation failures return 422 Unprocessable Entity ProblemDetail")
  void bookingValidationNegativeReturns422() throws Exception {
    // Missing required fields
    String invalidJson = """
        {
          "sender": {"name": "", "phone": "invalid-phone", "address": "short"},
          "recipient": {"name": "Kamala Silva", "phone": "0771234567", "address": "45 Temple Street, Kandy"},
          "parcel": {"weightKg": 25.0, "category": ""}
        }
        """;

    MvcResult result = mvc.perform(post("/api/v1/parcels")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidJson)
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors").exists())
        .andReturn();

    OpenApiResponseAssertions.assertMatches("/api/v1/parcels", "post", result);
  }

  @Test
  @DisplayName("POST /api/v1/parcels - Role security: anonymous returns 401, wrong role returns 403")
  void bookingSecurityRules() throws Exception {
    // 401 Anonymous
    mvc.perform(post("/api/v1/parcels")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BOOKING_JSON))
        .andExpect(status().isUnauthorized());

    // 403 Courier
    mvc.perform(post("/api/v1/parcels")
            .contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BOOKING_JSON)
            .with(jwt().jwt(j -> j.subject("courier-1")).authorities(new SimpleGrantedAuthority("ROLE_COURIER"))))
        .andExpect(status().isForbidden());
  }

  // --- 2. Fee Estimate Boundary Contracts ---

  @Test
  @DisplayName("POST /api/v1/parcels/estimate - Valid estimate returns 200 and matches OpenAPI schema")
  void feeEstimateReturns200AndConformsToSchema() throws Exception {
    when(pricingService.estimateFee(any())).thenReturn(new FeeEstimateResponse(
        new BigDecimal("1072.00"), "LKR 1,072.00", "LKR", 3,
        new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), new BigDecimal("120.00"), new BigDecimal("402.00"), null)));

    String requestJson = """
        {
          "pickupCity": "Colombo",
          "deliveryCity": "Kandy",
          "destinationType": "OTHER_DISTRICT",
          "weightKg": 2.0
        }
        """;

    MvcResult result = mvc.perform(post("/api/v1/parcels/estimate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(requestJson)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk())
        .andReturn();

    OpenApiResponseAssertions.assertMatches("/api/v1/parcels/estimate", "post", result);
  }

  @Test
  @DisplayName("POST /api/v1/parcels/estimate - Validation failure returns 400 with fieldErrors and invalidFields")
  void feeEstimateNegativeReturns400() throws Exception {
    // Missing weight, dimensions and parcelSize
    String invalidJson = """
        {
          "pickupCity": "Colombo",
          "deliveryCity": "Kandy"
        }
        """;

    MvcResult result = mvc.perform(post("/api/v1/parcels/estimate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidJson)
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors").exists())
        .andExpect(jsonPath("$.invalidFields").exists())
        .andReturn();

    OpenApiResponseAssertions.assertMatches("/api/v1/parcels/estimate", "post", result);
  }

  // --- 3. Zones & Pricing Admin Contracts ---

  @Test
  @DisplayName("GET /api/v1/zones and /zones/{code} - Return 200 matching schema and 404 for missing code")
  void zonesBoundaryContract() throws Exception {
    DeliveryZone zone = new DeliveryZone(UUID.randomUUID(), "COLOMBO", "Colombo Metro", "Western Province", true);
    when(zoneService.getActiveZones()).thenReturn(List.of(zone));
    when(zoneService.getZoneByCode("COLOMBO")).thenReturn(Optional.of(zone));
    when(zoneService.getZoneByCode("UNKNOWN")).thenReturn(Optional.empty());

    // GET /api/v1/zones
    MvcResult listResult = mvc.perform(get("/api/v1/zones")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk())
        .andReturn();
    OpenApiResponseAssertions.assertMatches("/api/v1/zones", "get", listResult);

    // GET /api/v1/zones/{code} - success
    MvcResult codeResult = mvc.perform(get("/api/v1/zones/COLOMBO")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isOk())
        .andReturn();
    OpenApiResponseAssertions.assertMatches("/api/v1/zones/{code}", "get", codeResult);

    // GET /api/v1/zones/{code} - not found 404
    mvc.perform(get("/api/v1/zones/UNKNOWN")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("GET /api/v1/admin/pricing-rules - DISPATCHER allowed, non-dispatcher gets 403")
  void pricingRuleAdminBoundary() throws Exception {
    when(pricingService.getActiveRule()).thenReturn(PricingRule.defaultStandard());

    // 200 DISPATCHER
    MvcResult result = mvc.perform(get("/api/v1/admin/pricing-rules")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_DISPATCHER"))))
        .andExpect(status().isOk())
        .andReturn();
    OpenApiResponseAssertions.assertMatches("/api/v1/admin/pricing-rules", "get", result);

    // 403 CUSTOMER
    mvc.perform(get("/api/v1/admin/pricing-rules")
            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isForbidden());
  }

  // --- 4. Merchant Order Contracts in S2 ---

  @Test
  @DisplayName("POST /api/v1/merchant/orders - Scoped to MERCHANT, new order returns 201 with Location")
  void merchantOrderBoundaryContract() throws Exception {
    MerchantOrderResponse response = new MerchantOrderResponse(
        PARCEL_ID, TRACKING_REF, "EXT-001", "merchant-sub-1", ParcelStatus.CREATED,
        new BigDecimal("1072.00"), "LKR", 3, CREATED_AT,
        new MerchantOrderResponse.Contact("Merchant Inc", "0771234567", "1 Galle Road", "Colombo"),
        new MerchantOrderResponse.Contact("Buyer One", "+94712345678", "2 Temple Road", "Kandy"),
        new MerchantOrderResponse.Parcel(new BigDecimal("1.5"), "Box", ParcelSize.SMALL, null));

    when(merchantOrderService.createOrder(eq("merchant-sub-1"), any()))
        .thenReturn(new OrderResult(response, true));

    String orderJson = """
        {
          "externalOrderRef": "EXT-001",
          "sender": {"name": "Merchant Inc", "phone": "0771234567", "address": "1 Galle Road", "city": "Colombo"},
          "recipient": {"name": "Buyer One", "phone": "+94712345678", "address": "2 Temple Road", "city": "Kandy"},
          "parcel": {"weightKg": 1.5, "category": "Box", "size": "SMALL"},
          "destinationType": "OTHER_DISTRICT"
        }
        """;

    // 201 with Location for MERCHANT
    MvcResult result = mvc.perform(post("/api/v1/merchant/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderJson)
            .with(jwt().jwt(j -> j.subject("merchant-sub-1")).authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"))))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/merchant/orders/" + PARCEL_ID))
        .andReturn();
    OpenApiResponseAssertions.assertMatches("/api/v1/merchant/orders", "post", result);

    // 403 for CUSTOMER
    mvc.perform(post("/api/v1/merchant/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(orderJson)
            .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
        .andExpect(status().isForbidden());
  }
}
