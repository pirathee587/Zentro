package com.zentro.parcelpricing.parcel.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.dto.CreateMerchantOrderRequest;
import com.zentro.parcelpricing.parcel.dto.MerchantOrderResponse;
import com.zentro.parcelpricing.exception.DuplicateExternalReferenceException;
import com.zentro.parcelpricing.exception.MerchantOrderExceptionHandler;
import com.zentro.parcelpricing.exception.PricingUnavailableException;
import com.zentro.parcelpricing.integration.IdentityMerchantClient;
import com.zentro.parcelpricing.parcel.service.MerchantOrderService;
import com.zentro.parcelpricing.parcel.service.MerchantOrderService.OrderResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(controllers = MerchantOrderController.class)
@Import({com.zentro.security.config.ZentroSecurityAutoConfiguration.class, MerchantOrderExceptionHandler.class})
@TestPropertySource(properties = {
    "zentro.security.issuer-uri=http://localhost:8180/realms/zentro",
    "zentro.security.audience=zentro-api"
})
class MerchantOrderControllerTest {
  private static final String URL = "/api/v1/merchant/orders";
  private static final String PROBLEM_JSON = "application/problem+json";

  private static final String VALID_BODY = """
      {
        "externalOrderRef": "ORD-2026-11-001",
        "sender": {"name": "Apex Electronics", "phone": "0112345678", "address": "77 Galle Road, Colombo 03"},
        "recipient": {"name": "Nimal Perera", "phone": "+94 77 123 4567", "address": "15 Peradeniya Road, Kandy"},
        "parcel": {"weightKg": 2.0, "category": "Electronics", "size": "MEDIUM"},
        "destinationType": "OTHER_DISTRICT"
      }
      """;

  @Autowired MockMvc mvc;
  @MockBean MerchantOrderService orderService;
  @MockBean IdentityMerchantClient identityClient;
  @MockBean JwtDecoder jwtDecoder;

  @Test
  @DisplayName("Approved merchant can create an order: returns 201, Location header, and order details")
  void approvedMerchantCanCreateOrder() throws Exception {
    MerchantOrderResponse order = sampleOrder();
    when(orderService.createOrder(eq("merchant-123"), any())).thenReturn(new OrderResult(order, true));

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/merchant/orders/" + order.id()))
        .andExpect(jsonPath("$.id").value(order.id().toString()))
        .andExpect(jsonPath("$.trackingRef").value("ZT-ABCDEFGH12"))
        .andExpect(jsonPath("$.externalOrderRef").value("ORD-2026-11-001"))
        .andExpect(jsonPath("$.merchantId").value("merchant-123"))
        .andExpect(jsonPath("$.status").value("CREATED"))
        .andExpect(jsonPath("$.fee").value(1072.00))
        .andExpect(jsonPath("$.currency").value("LKR"))
        .andExpect(jsonPath("$.estimatedDays").value(3))
        .andExpect(jsonPath("$.createdAt").exists())
        .andExpect(jsonPath("$.recipient.name").value("Nimal Perera"));

    verify(identityClient).requireApprovedMerchant(any());
  }

  @Test
  @DisplayName("Replaying the same externalOrderRef with matching body returns 200 without Location header")
  void replayReturnsOk() throws Exception {
    MerchantOrderResponse order = sampleOrder();
    when(orderService.createOrder(eq("merchant-123"), any())).thenReturn(new OrderResult(order, false));

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"))
        .andExpect(jsonPath("$.trackingRef").value("ZT-ABCDEFGH12"))
        .andExpect(jsonPath("$.externalOrderRef").value("ORD-2026-11-001"));

    verify(identityClient).requireApprovedMerchant(any());
  }

  @Test
  @DisplayName("Duplicate externalOrderRef with different body throws 409 Conflict")
  void duplicateExternalRefWithDifferentBodyReturns409() throws Exception {
    when(orderService.createOrder(eq("merchant-123"), any()))
        .thenThrow(new DuplicateExternalReferenceException());

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.title").value("Duplicate external reference"));
  }

  @Test
  @DisplayName("Unapproved merchant receives 403 Forbidden")
  void unapprovedMerchantReturns403() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant application is not approved."))
        .when(identityClient).requireApprovedMerchant(any());

    mvc.perform(post(URL).with(merchant("unapproved-merchant")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Merchant application is not approved."));

    verifyNoInteractions(orderService);
  }

  @Test
  @DisplayName("Inactive merchant account receives 403 Forbidden")
  void inactiveMerchantAccountReturns403() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant user account is not active."))
        .when(identityClient).requireApprovedMerchant(any());

    mvc.perform(post(URL).with(merchant("inactive-merchant")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.detail").value("Merchant user account is not active."));

    verifyNoInteractions(orderService);
  }

  @Test
  @DisplayName("Anonymous request returns 401 Unauthorized")
  void anonymousReturns401() throws Exception {
    mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isUnauthorized());

    verifyNoInteractions(orderService);
    verifyNoInteractions(identityClient);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_CUSTOMER", "ROLE_COURIER", "ROLE_DISPATCHER", "ROLE_SERVICE", "ROLE_ADMIN"})
  @DisplayName("Non-merchant roles including ADMIN return 403 Forbidden")
  void nonMerchantRolesReturn403(String role) throws Exception {
    mvc.perform(post(URL).with(jwt().jwt(j -> j.subject("someone")).authorities(new SimpleGrantedAuthority(role)))
            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isForbidden());

    verifyNoInteractions(orderService);
    verifyNoInteractions(identityClient);
  }

  @Test
  @DisplayName("Identity verification service outage returns 503 Service Unavailable")
  void identityServiceOutageReturns503() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity verification service is unavailable."))
        .when(identityClient).requireApprovedMerchant(any());

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isServiceUnavailable());

    verifyNoInteractions(orderService);
  }

  @Test
  @DisplayName("Merchant subject is strictly scoped to token subject; client-sent merchant fields are ignored")
  void merchantIdComesFromTokenSubject() throws Exception {
    when(orderService.createOrder(any(), any())).thenReturn(new OrderResult(sampleOrder(), true));
    String bodyWithForgedField = VALID_BODY.replaceFirst("\\{",
        "{\"merchantId\": \"spoofed-merchant-id\",");

    mvc.perform(post(URL).with(merchant("actual-merchant-subject")).contentType(MediaType.APPLICATION_JSON)
            .content(bodyWithForgedField))
        .andExpect(status().isCreated());

    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(orderService).createOrder(captor.capture(), any(CreateMerchantOrderRequest.class));
    org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo("actual-merchant-subject");
  }

  @ParameterizedTest(name = "{0} -> 422 on {1}")
  @CsvSource(delimiter = '|', value = {
      "\"phone\": \"0112345678\"|\"phone\": \"12345\"|sender.phone",
      "\"phone\": \"+94 77 123 4567\"|\"phone\": \"077123456\"|recipient.phone",
      "\"address\": \"15 Peradeniya Road, Kandy\"|\"address\": \"short\"|recipient.address",
      "\"name\": \"Nimal Perera\"|\"name\": \"  \"|recipient.name",
      "\"weightKg\": 2.0|\"weightKg\": 25|parcel.weightKg",
      "\"weightKg\": 2.0|\"weightKg\": 0.05|parcel.weightKg",
      "\"category\": \"Electronics\"|\"category\": \"\"|parcel.category",
      "\"externalOrderRef\": \"ORD-2026-11-001\"|\"externalOrderRef\": \"   \"|externalOrderRef"
  })
  @DisplayName("Invalid field returns 422 with fieldErrors map")
  void invalidFieldReturns422(String original, String replacement, String path) throws Exception {
    String body = VALID_BODY.replace(original, replacement);

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.fieldErrors['" + path + "']").exists());

    verifyNoInteractions(orderService);
  }

  @Test
  @DisplayName("Validation failure does not leak personal data in response")
  void validationFailureDoesNotLeakPersonalData() throws Exception {
    String body = VALID_BODY.replace("\"name\": \"Nimal Perera\", ", "")
        .replace("\"phone\": \"0112345678\"", "\"phone\": \"0119876\"");

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.fieldErrors['recipient.name']").value("Name is required."))
        .andExpect(content().string(not(containsString("0119876"))))
        .andExpect(content().string(not(containsString("Peradeniya Road"))));
  }

  @Test
  @DisplayName("Malformed JSON returns 400 Bad Request")
  void malformedJsonReturns400() throws Exception {
    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content("{\"sender\": "))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

    verifyNoInteractions(orderService);
  }

  @Test
  @DisplayName("Pricing unavailable returns 503")
  void pricingUnavailableReturns503() throws Exception {
    when(orderService.createOrder(any(), any()))
        .thenThrow(new PricingUnavailableException(new QueryTimeoutException("down")));

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
  }

  @Test
  @DisplayName("Database storage failure returns 503")
  void databaseFailureReturns503() throws Exception {
    when(orderService.createOrder(any(), any()))
        .thenThrow(new QueryTimeoutException("down"));

    mvc.perform(post(URL).with(merchant("merchant-123")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  @DisplayName("GET /api/v1/merchant/orders/{id} returns order when owned by caller")
  void getOrderByIdSuccess() throws Exception {
    UUID orderId = UUID.randomUUID();
    MerchantOrderResponse order = sampleOrder(orderId, "merchant-123");
    when(orderService.findById(orderId, "merchant-123")).thenReturn(Optional.of(order));

    mvc.perform(get(URL + "/" + orderId).with(merchant("merchant-123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orderId.toString()))
        .andExpect(jsonPath("$.externalOrderRef").value("ORD-2026-11-001"))
        .andExpect(jsonPath("$.merchantId").value("merchant-123"));

    verify(identityClient).requireApprovedMerchant(any());
  }

  @Test
  @DisplayName("GET /api/v1/merchant/orders/{id} returns 404 when order belongs to different merchant")
  void getOrderByIdBelongingToAnotherMerchantReturns404() throws Exception {
    UUID orderId = UUID.randomUUID();
    when(orderService.findById(orderId, "merchant-attacker")).thenReturn(Optional.empty());

    mvc.perform(get(URL + "/" + orderId).with(merchant("merchant-attacker")))
        .andExpect(status().isNotFound());

    verify(identityClient).requireApprovedMerchant(any());
  }

  @Test
  @DisplayName("GET /api/v1/merchant/orders lists orders scoped to merchant")
  void listOrdersSuccess() throws Exception {
    when(orderService.listByMerchant(eq("merchant-123"), anyInt()))
        .thenReturn(List.of(sampleOrder()));

    mvc.perform(get(URL).with(merchant("merchant-123")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].externalOrderRef").value("ORD-2026-11-001"));

    verify(identityClient).requireApprovedMerchant(any());
  }

  private static RequestPostProcessor merchant(String subject) {
    return jwt().jwt(j -> j.subject(subject).tokenValue("mock-jwt-token"))
        .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT"));
  }

  private static MerchantOrderResponse sampleOrder() {
    return sampleOrder(UUID.fromString("3f1c2b7e-8a4d-4c1e-9b2a-5d6e7f8a9b0c"), "merchant-123");
  }

  private static MerchantOrderResponse sampleOrder(UUID id, String merchantId) {
    return new MerchantOrderResponse(
        id,
        "ZT-ABCDEFGH12",
        "ORD-2026-11-001",
        merchantId,
        ParcelStatus.CREATED,
        new BigDecimal("1072.00"),
        "LKR",
        3,
        OffsetDateTime.parse("2026-11-03T10:15:30Z"),
        new MerchantOrderResponse.Contact("Apex Electronics", "0112345678", "77 Galle Road, Colombo 03", null),
        new MerchantOrderResponse.Contact("Nimal Perera", "+94771234567", "15 Peradeniya Road, Kandy", null),
        new MerchantOrderResponse.Parcel(new BigDecimal("2.00"), "Electronics", ParcelSize.MEDIUM, null));
  }
}
