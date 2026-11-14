package com.zentro.parcelpricing.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller-level security and boundary test verifying ownership isolation,
 * IDOR prevention, unauthenticated rejection, privileged access, and idempotent retrieval.
 */
@ExtendWith(MockitoExtension.class)
class ReceiptAuthorizationAndBoundaryTest {

  @Mock private ReceiptGenerationService receiptService;

  private ReceiptController controller;
  private final UUID parcelId = UUID.randomUUID();
  private final String trackingRef = "ZT-7K3QX9M2PA";

  @BeforeEach
  void setUp() {
    controller = new ReceiptController(receiptService);
  }

  private JwtAuthenticationToken createAuthToken(String subject, String role) {
    Jwt jwt = Jwt.withTokenValue("mock-jwt-token")
        .header("alg", "RS256")
        .subject(subject)
        .build();
    return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
  }

  private Receipt sampleReceipt(String customerSubject, String merchantId) {
    return new Receipt(
        UUID.randomUUID(),
        "REC-7K3QX9",
        parcelId,
        trackingRef,
        customerSubject,
        merchantId,
        new Receipt.Contact("Sender", "0771112222", "Colombo 3", "Colombo"),
        new Receipt.Contact("Recipient", "0773334444", "Kandy Rd", "Kandy"),
        new BigDecimal("1250.00"),
        "LKR",
        "Paid",
        "Credit Card (•••• 4242)",
        OffsetDateTime.now(),
        "Package",
        "Standard"
    );
  }

  @Test
  @DisplayName("Foreign customer access returns 404 to prevent resource enumeration")
  void foreignCustomerAccessReturns404() {
    JwtAuthenticationToken token = createAuthToken("cust-mallory", "CUSTOMER");
    when(receiptService.generateReceiptForCustomer(parcelId, "cust-mallory")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getReceipt(parcelId, (Jwt) token.getPrincipal(), token))
        .isInstanceOf(ResponseStatusException.class)
        .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Foreign merchant access returns 404 to prevent resource enumeration")
  void foreignMerchantAccessReturns404() {
    JwtAuthenticationToken token = createAuthToken("merchant-rival", "MERCHANT");
    when(receiptService.generateReceiptForMerchant(parcelId, "merchant-rival")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getReceipt(parcelId, (Jwt) token.getPrincipal(), token))
        .isInstanceOf(ResponseStatusException.class)
        .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
  }

  @Test
  @DisplayName("Unauthenticated request with null or blank subject throws 401")
  void unauthenticatedRequestThrows401() {
    Jwt jwtWithoutSubject = Jwt.withTokenValue("mock-jwt").header("alg", "RS256").claim("foo", "bar").build();
    JwtAuthenticationToken token = new JwtAuthenticationToken(jwtWithoutSubject, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

    assertThatThrownBy(() -> controller.getReceipt(parcelId, jwtWithoutSubject, token))
        .isInstanceOf(ResponseStatusException.class)
        .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.UNAUTHORIZED);
  }

  @Test
  @DisplayName("Privileged DISPATCHER can retrieve any parcel receipt")
  void privilegedDispatcherCanRetrieveReceipt() {
    JwtAuthenticationToken dispatcherToken = createAuthToken("dispatcher-user-1", "DISPATCHER");
    Receipt receipt = sampleReceipt("cust-alice", null);
    ReceiptResponse responseDto = new ReceiptResponse(
        receipt.id(), receipt.receiptNumber(), parcelId, trackingRef,
        receipt.fee(), "LKR 1,250.00", "LKR", "Paid", "Credit Card",
        "/api/v1/parcels/" + parcelId + "/receipt/pdf", "2026-11-10", "10 Nov 2026",
        "Sender", "Recipient");

    when(receiptService.generateReceipt(parcelId)).thenReturn(Optional.of(receipt));
    when(receiptService.toResponse(receipt)).thenReturn(responseDto);

    ResponseEntity<ReceiptResponse> response = controller.getReceipt(
        parcelId, (Jwt) dispatcherToken.getPrincipal(), dispatcherToken);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().trackingRef()).isEqualTo(trackingRef);
    verify(receiptService).generateReceipt(parcelId);
  }

  @Test
  @DisplayName("Idempotent receipt retrieval returns identical snapshot across repeated requests")
  void idempotentReceiptRetrievalReturnsConsistentSnapshot() {
    JwtAuthenticationToken customerToken = createAuthToken("cust-alice", "CUSTOMER");
    Receipt receipt = sampleReceipt("cust-alice", null);
    ReceiptResponse responseDto = new ReceiptResponse(
        receipt.id(), receipt.receiptNumber(), parcelId, trackingRef,
        receipt.fee(), "LKR 1,250.00", "LKR", "Paid", "Credit Card",
        "/api/v1/parcels/" + parcelId + "/receipt/pdf", "2026-11-10", "10 Nov 2026",
        "Sender", "Recipient");

    when(receiptService.generateReceiptForCustomer(parcelId, "cust-alice")).thenReturn(Optional.of(receipt));
    when(receiptService.toResponse(receipt)).thenReturn(responseDto);

    // Call 1
    ResponseEntity<ReceiptResponse> res1 = controller.getReceipt(parcelId, (Jwt) customerToken.getPrincipal(), customerToken);
    // Call 2
    ResponseEntity<ReceiptResponse> res2 = controller.getReceipt(parcelId, (Jwt) customerToken.getPrincipal(), customerToken);

    assertThat(res1.getBody()).isEqualTo(res2.getBody());
    assertThat(res1.getBody().receiptNumber()).isEqualTo("REC-7K3QX9");
  }

  @Test
  @DisplayName("Missing parcel ID throws 404 NOT_FOUND")
  void missingParcelIdThrows404() {
    JwtAuthenticationToken customerToken = createAuthToken("cust-alice", "CUSTOMER");
    when(receiptService.generateReceiptForCustomer(parcelId, "cust-alice")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> controller.getReceipt(parcelId, (Jwt) customerToken.getPrincipal(), customerToken))
        .isInstanceOf(ResponseStatusException.class)
        .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.NOT_FOUND);
  }
}
