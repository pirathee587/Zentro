package com.zentro.parcelpricing.parcel.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.parcel.dto.ParcelBookingResponse;
import com.zentro.parcelpricing.exception.BookingExceptionHandler;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService;
import com.zentro.parcelpricing.parcel.service.ParcelBookingService.BookingResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Exact validation edges of POST /api/v1/parcels (same rules as the customer form): what is accepted at the
 * limit and rejected just past it, and that every 422 lists field errors without echoing personal data.
 * The booking service is mocked; validation and the booking error handler are real.
 */
@WebMvcTest(controllers = ParcelBookingController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class, BookingExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class ParcelBookingValidationBoundaryTest {
  private static final String URL = "/api/v1/parcels";
  private static final String PROBLEM_JSON = "application/problem+json";
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired MockMvc mvc;
  @MockBean ParcelBookingService bookingService;
  @MockBean JwtDecoder jwtDecoder;

  @BeforeEach
  void serviceAcceptsEverything() {
    reset(bookingService);
    when(bookingService.book(anyString(), any(), any())).thenReturn(new BookingResult(new ParcelBookingResponse(
        UUID.randomUUID(), "ZT-ABCDEFGH12", ParcelStatus.CREATED, new BigDecimal("670.00"), "LKR", 2,
        OffsetDateTime.parse("2026-11-05T10:00:00Z"),
        new ParcelBookingResponse.Contact("Sender Contact", "0771234567", "12 Galle Road, Colombo 03", null),
        new ParcelBookingResponse.Contact("Test Recipient", "0712345678", "45 Temple Street, Kandy", null),
        new ParcelBookingResponse.Parcel(new BigDecimal("2.00"), "Small Box", ParcelSize.SMALL, null)), true));
  }

  private static ObjectNode validBody() {
    ObjectNode body = MAPPER.createObjectNode();
    body.putObject("sender").put("name", "Sender Contact").put("phone", "0771234567")
        .put("address", "12 Galle Road, Colombo 03");
    body.putObject("recipient").put("name", "Test Recipient").put("phone", "0712345678")
        .put("address", "45 Temple Street, Kandy");
    body.putObject("parcel").put("weightKg", 2.0).put("category", "Small Box").put("size", "SMALL");
    body.put("destinationType", "SAME_CITY");
    return body;
  }

  private static ObjectNode body(Consumer<ObjectNode> change) {
    ObjectNode body = validBody();
    change.accept(body);
    return body;
  }

  private static ObjectNode node(ObjectNode body, String path) {
    return (ObjectNode) body.get(path);
  }

  private ResultActions book(ObjectNode body) throws Exception {
    return mvc.perform(post(URL)
        .with(jwt().jwt(j -> j.subject("customer-1")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
        .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
  }

  private void assertAccepted(ObjectNode body) throws Exception {
    book(body).andExpect(status().isCreated());
  }

  /** 422 problem+json with a fieldErrors entry for the path, nothing booked, and the bad value not echoed. */
  private void assertRejected(ObjectNode body, String path, String submittedValue) throws Exception {
    MvcResult result = book(body)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.fieldErrors['" + path + "']").isNotEmpty())
        .andReturn();
    if (submittedValue != null && submittedValue.trim().length() > 3) {
      assertThat(result.getResponse().getContentAsString()).doesNotContain(submittedValue.trim());
    }
    verifyNoInteractions(bookingService);
  }

  // ---- weight ----

  @ParameterizedTest(name = "weight {0} kg is accepted")
  @ValueSource(strings = {"0.1", "0.10", "19.99", "20", "20.00"})
  @DisplayName("Weight from 0.1 to 20 kg inclusive is accepted")
  void weightAtTheLimitsIsAccepted(String weight) throws Exception {
    assertAccepted(body(b -> node(b, "parcel").put("weightKg", new BigDecimal(weight))));
  }

  @ParameterizedTest(name = "weight {0} kg is rejected")
  @ValueSource(strings = {"0.09", "0.099", "20.01", "20.001", "0", "-1"})
  @DisplayName("Weight just outside 0.1-20 kg, zero or negative is rejected with 422")
  void weightOutsideTheLimitsIsRejected(String weight) throws Exception {
    assertRejected(body(b -> node(b, "parcel").put("weightKg", new BigDecimal(weight))), "parcel.weightKg", null);
  }

  @Test
  @DisplayName("Missing weight is rejected with 422")
  void missingWeightIsRejected() throws Exception {
    assertRejected(body(b -> node(b, "parcel").remove("weightKg")), "parcel.weightKg", null);
  }

  // ---- phone ----

  @ParameterizedTest(name = "phone \"{0}\" is accepted")
  @ValueSource(strings = {"0771234567", "+94771234567", "077 123 4567", "077-123-4567", "+94 77-123 4567",
      " 0771234567 "})
  @DisplayName("Sri Lankan numbers with spaces or dashes are accepted (the service stores them without separators)")
  void phoneWithSeparatorsIsAccepted(String phone) throws Exception {
    assertAccepted(body(b -> node(b, "recipient").put("phone", phone)));
  }

  @ParameterizedTest(name = "phone \"{0}\" is rejected")
  // Values chosen so they do not overlap the example numbers in the standard hint text.
  @ValueSource(strings = {"076543210", "07654321098", "765432109", "0094765432109", "+9476543210",
      "07654a3210", "(076) 5432109", ""})
  @DisplayName("Numbers with the wrong length, prefix or characters are rejected with 422")
  void invalidPhoneIsRejected(String phone) throws Exception {
    assertRejected(body(b -> node(b, "sender").put("phone", phone)), "sender.phone", phone);
  }

  // ---- address ----

  @ParameterizedTest(name = "address \"{0}\" is accepted")
  @ValueSource(strings = {"No 12 Rd", "  No 12 Rd  "})
  @DisplayName("An address of exactly 8 characters (after trimming) is accepted")
  void eightCharacterAddressIsAccepted(String address) throws Exception {
    assertAccepted(body(b -> node(b, "recipient").put("address", address)));
  }

  @ParameterizedTest(name = "address \"{0}\" is rejected")
  @ValueSource(strings = {"No 1 Rd", "  No 1 Rd  ", "        ", "Kandy"})
  @DisplayName("An address shorter than 8 characters (after trimming) is rejected with 422")
  void shortAddressIsRejected(String address) throws Exception {
    assertRejected(body(b -> node(b, "recipient").put("address", address)), "recipient.address", address);
  }

  // ---- maximum lengths ----

  @ParameterizedTest(name = "{0} at {1} characters is accepted")
  @CsvSource({
      "sender.name, 100", "recipient.name, 100", "sender.address, 255", "recipient.address, 255",
      "sender.city, 100", "recipient.city, 100", "parcel.category, 40", "parcel.description, 255", "notes, 500"
  })
  @DisplayName("Text fields at their maximum length are accepted")
  void maximumLengthIsAccepted(String path, int length) throws Exception {
    assertAccepted(body(b -> setText(b, path, "x".repeat(length))));
  }

  @ParameterizedTest(name = "{0} at {1} characters is rejected")
  @CsvSource({
      "sender.name, 101", "recipient.name, 101", "sender.address, 256", "recipient.address, 256",
      "sender.city, 101", "recipient.city, 101", "parcel.category, 41", "parcel.description, 256", "notes, 501"
  })
  @DisplayName("Text fields one character over their maximum are rejected with 422")
  void overMaximumLengthIsRejected(String path, int length) throws Exception {
    assertRejected(body(b -> setText(b, path, "y".repeat(length))), path, "y".repeat(length));
  }

  private static void setText(ObjectNode body, String path, String value) {
    int dot = path.indexOf('.');
    if (dot < 0) {
      body.put(path, value);
    } else {
      node(body, path.substring(0, dot)).put(path.substring(dot + 1), value);
    }
  }

  // ---- required parts ----

  @ParameterizedTest(name = "missing {0}")
  @ValueSource(strings = {"sender", "recipient", "parcel"})
  @DisplayName("A missing sender, recipient or parcel section is rejected with 422")
  void missingSectionIsRejected(String section) throws Exception {
    assertRejected(body(b -> b.remove(section)), section, null);
  }

  @ParameterizedTest(name = "missing {0}")
  @ValueSource(strings = {"sender.name", "recipient.name", "sender.phone", "recipient.address", "parcel.category"})
  @DisplayName("A missing required field is rejected with 422")
  void missingRequiredFieldIsRejected(String path) throws Exception {
    int dot = path.indexOf('.');
    assertRejected(body(b -> node(b, path.substring(0, dot)).remove(path.substring(dot + 1))), path, null);
  }

  @Test
  @DisplayName("An empty body lists every missing section at once")
  void emptyBodyListsAllSections() throws Exception {
    book(MAPPER.createObjectNode())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors.sender").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.recipient").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors.parcel").isNotEmpty());
    verifyNoInteractions(bookingService);
  }

  @Test
  @DisplayName("Several invalid fields are reported together and none of the submitted values is echoed")
  void severalErrorsWithoutPersonalData() throws Exception {
    ObjectNode body = body(b -> {
      node(b, "sender").put("phone", "0119876");
      node(b, "recipient").put("name", "").put("address", "Qwzx Ln");
      node(b, "parcel").put("weightKg", 25);
    });
    String response = book(body)
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors['sender.phone']").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors['recipient.name']").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors['recipient.address']").isNotEmpty())
        .andExpect(jsonPath("$.fieldErrors['parcel.weightKg']").isNotEmpty())
        .andReturn().getResponse().getContentAsString();
    assertThat(response).doesNotContain("0119876", "Qwzx Ln", "Sender Contact", "12 Galle Road", "0712345678");
  }

  // ---- unknown fields ----

  @Test
  @DisplayName("Unknown JSON fields are ignored and the booking is accepted")
  void unknownFieldsAreIgnored() throws Exception {
    assertAccepted(body(b -> {
      b.put("promoCode", "FREE");
      node(b, "parcel").put("colour", "red");
      node(b, "sender").put("email", "someone@example.com");
    }));
    ArgumentCaptor<CreateParcelBookingRequest> request = ArgumentCaptor.forClass(CreateParcelBookingRequest.class);
    verify(bookingService).book(anyString(), request.capture(), any());
    assertThat(request.getValue().parcel().category()).isEqualTo("Small Box");
  }
}
