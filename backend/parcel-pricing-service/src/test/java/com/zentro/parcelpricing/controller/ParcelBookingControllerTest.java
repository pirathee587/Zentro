package com.zentro.parcelpricing.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.parcelpricing.domain.ParcelSize;
import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.error.BookingExceptionHandler;
import com.zentro.parcelpricing.error.IdempotencyConflictException;
import com.zentro.parcelpricing.error.PricingUnavailableException;
import com.zentro.parcelpricing.service.ParcelBookingService;
import com.zentro.parcelpricing.service.ParcelBookingService.BookingResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = ParcelBookingController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class, BookingExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class ParcelBookingControllerTest {
  private static final String URL = "/api/v1/parcels";
  private static final String PROBLEM_JSON = "application/problem+json";

  private static final String VALID_BODY = """
      {
        "sender": {"name": "Sender Contact", "phone": "0771234567", "address": "12 Galle Road, Colombo 03"},
        "recipient": {"name": "Kamala Silva", "phone": "+94 77 123 4567", "address": "45 Temple Street, Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Small Box", "size": "SMALL"},
        "destinationType": "OTHER_DISTRICT"
      }
      """;

  @Autowired MockMvc mvc;
  @MockBean ParcelBookingService bookingService;
  @MockBean JwtDecoder jwtDecoder;

  @Test
  @DisplayName("Customer booking returns 201, Location header and the booking body")
  void customerCanBook() throws Exception {
    ParcelBookingResponse booking = booking();
    when(bookingService.book(eq("customer-123"), any(), isNull())).thenReturn(new BookingResult(booking, true));

    mvc.perform(post(URL).with(customer("customer-123")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/parcels/" + booking.id()))
        .andExpect(jsonPath("$.id").value(booking.id().toString()))
        .andExpect(jsonPath("$.trackingRef").value("ZT-ABCDEFGH12"))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.fee").value(1072.00))
        .andExpect(jsonPath("$.currency").value("LKR"))
        .andExpect(jsonPath("$.estimatedDays").value(3))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.recipient.name").value("Kamala Silva"))
        .andExpect(jsonPath("$.parcel.size").value("SMALL"));
  }

  @Test
  @DisplayName("Customer is taken from the token subject; client-sent customer, fee and status are ignored")
  void customerComesFromTokenSubject() throws Exception {
    when(bookingService.book(any(), any(), any())).thenReturn(new BookingResult(booking(), true));
    String bodyWithForgedFields = VALID_BODY.replaceFirst("\\{",
        "{\"customerId\": \"attacker\", \"customerSubject\": \"attacker\", \"fee\": 1, \"status\": \"DELIVERED\", "
            + "\"trackingRef\": \"ZT-FORGED0000\",");

    mvc.perform(post(URL).with(customer("real-subject")).contentType(MediaType.APPLICATION_JSON)
            .content(bodyWithForgedFields))
        .andExpect(status().isCreated());

    ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
    verify(bookingService).book(subject.capture(), any(CreateParcelBookingRequest.class), isNull());
    org.assertj.core.api.Assertions.assertThat(subject.getValue()).isEqualTo("real-subject");
  }

  @Test
  @DisplayName("Replayed idempotent request returns 200 with the original booking")
  void replayReturnsOk() throws Exception {
    when(bookingService.book(eq("customer-123"), any(), eq("key-1"))).thenReturn(new BookingResult(booking(), false));

    mvc.perform(post(URL).with(customer("customer-123")).header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.trackingRef").value("ZT-ABCDEFGH12"));
  }

  @ParameterizedTest(name = "{0} -> 422 on {1}")
  @CsvSource(delimiter = '|', value = {
      "\"phone\": \"0771234567\"|\"phone\": \"12345\"|sender.phone",
      "\"phone\": \"+94 77 123 4567\"|\"phone\": \"077123456\"|recipient.phone",
      "\"address\": \"45 Temple Street, Kandy\"|\"address\": \"short\"|recipient.address",
      "\"address\": \"12 Galle Road, Colombo 03\"|\"address\": \"   a     \"|sender.address",
      "\"name\": \"Kamala Silva\"|\"name\": \"  \"|recipient.name",
      "\"weightKg\": 2.0|\"weightKg\": 25|parcel.weightKg",
      "\"weightKg\": 2.0|\"weightKg\": 0.05|parcel.weightKg",
      "\"category\": \"Small Box\"|\"category\": \"\"|parcel.category",
  })
  @DisplayName("Invalid field returns 422 with fieldErrors keyed by request path")
  void invalidFieldReturns422(String original, String replacement, String path) throws Exception {
    String body = VALID_BODY.replace(original, replacement);

    mvc.perform(post(URL).with(customer("customer-123")).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.fieldErrors['" + path + "']").exists());
    verifyNoInteractions(bookingService);
  }

  @Test
  @DisplayName("Missing recipient name returns 422 and the message does not echo personal data")
  void missingRecipientNameReturns422WithoutPersonalData() throws Exception {
    String body = VALID_BODY.replace("\"name\": \"Kamala Silva\", ", "")
        .replace("\"phone\": \"0771234567\"", "\"phone\": \"0119876\"");

    mvc.perform(post(URL).with(customer("customer-123")).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors['recipient.name']").value("Name is required."))
        .andExpect(jsonPath("$.fieldErrors['sender.phone']").exists())
        .andExpect(content().string(not(containsString("0119876"))))
        .andExpect(content().string(not(containsString("Galle Road"))));
  }

  @Test
  @DisplayName("Missing parcel section returns 422")
  void missingParcelReturns422() throws Exception {
    String body = """
        {"sender": {"name": "A", "phone": "0771234567", "address": "12 Galle Road"},
         "recipient": {"name": "B", "phone": "0771234567", "address": "45 Temple Street"}}
        """;
    mvc.perform(post(URL).with(customer("customer-123")).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors.parcel").exists());
  }

  @Test
  @DisplayName("Malformed JSON returns 400")
  void malformedJsonReturns400() throws Exception {
    mvc.perform(post(URL).with(customer("customer-123")).contentType(MediaType.APPLICATION_JSON).content("{\"sender\": "))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    verifyNoInteractions(bookingService);
  }

  @Test
  @DisplayName("Unknown enum value returns 400")
  void badEnumReturns400() throws Exception {
    mvc.perform(post(URL).with(customer("customer-123")).contentType(MediaType.APPLICATION_JSON)
            .content(VALID_BODY.replace("\"SMALL\"", "\"HUGE\"")))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(bookingService);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 101})
  @DisplayName("Idempotency-Key that is blank or longer than 100 characters returns 400")
  void invalidIdempotencyKeyReturns400(int length) throws Exception {
    String key = length == 0 ? " " : "k".repeat(length);
    mvc.perform(post(URL).with(customer("customer-123")).header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(bookingService);
  }

  @Test
  @DisplayName("Anonymous request returns 401")
  void anonymousReturns401() throws Exception {
    mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(bookingService);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_MERCHANT", "ROLE_COURIER", "ROLE_DISPATCHER", "ROLE_SERVICE"})
  @DisplayName("Non-customer roles return 403")
  void nonCustomerRolesReturn403(String role) throws Exception {
    mvc.perform(post(URL).with(jwt().jwt(j -> j.subject("someone")).authorities(new SimpleGrantedAuthority(role)))
            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isForbidden());
    verifyNoInteractions(bookingService);
  }

  @Test
  @DisplayName("Idempotency-Key reused with a different body returns 409")
  void idempotencyConflictReturns409() throws Exception {
    when(bookingService.book(any(), any(), eq("key-1"))).thenThrow(new IdempotencyConflictException());

    mvc.perform(post(URL).with(customer("customer-123")).header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  @DisplayName("Pricing unavailable returns 503")
  void pricingUnavailableReturns503() throws Exception {
    when(bookingService.book(any(), any(), any()))
        .thenThrow(new PricingUnavailableException(new QueryTimeoutException("down")));

    perform(post(URL)).andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  @DisplayName("Pricing rejecting the parcel data returns 422")
  void pricingRejectionReturns422() throws Exception {
    when(bookingService.book(any(), any(), any())).thenThrow(new IllegalArgumentException("Unsupported currency"));

    perform(post(URL)).andExpect(status().isUnprocessableEntity());
  }

  @Test
  @DisplayName("Database failure while saving returns 503")
  void databaseFailureReturns503() throws Exception {
    when(bookingService.book(any(), any(), any())).thenThrow(new QueryTimeoutException("down"));

    perform(post(URL)).andExpect(status().isServiceUnavailable());
  }

  private org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request)
      throws Exception {
    return mvc.perform(request.with(customer("customer-123"))
        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY));
  }

  private static RequestPostProcessor customer(String subject) {
    return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
  }

  private static ParcelBookingResponse booking() {
    return new ParcelBookingResponse(
        UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c"),
        "ZT-ABCDEFGH12",
        ParcelStatus.CREATED,
        new BigDecimal("1072.00"),
        "LKR",
        3,
        OffsetDateTime.parse("2026-10-28T10:15:30Z"),
        new ParcelBookingResponse.Contact("Sender Contact", "0771234567", "12 Galle Road, Colombo 03", null),
        new ParcelBookingResponse.Contact("Kamala Silva", "+94771234567", "45 Temple Street, Kandy", null),
        new ParcelBookingResponse.Parcel(new BigDecimal("2.00"), "Small Box", ParcelSize.SMALL, null));
  }
}
