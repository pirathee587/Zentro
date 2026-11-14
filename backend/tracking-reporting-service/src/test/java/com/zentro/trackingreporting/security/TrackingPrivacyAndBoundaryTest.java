package com.zentro.trackingreporting.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import com.zentro.trackingreporting.tracking.service.TrackingService;
import com.zentro.trackingreporting.tracking.service.TrackingStreamService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * G04/G37/X02 Ownership and Privacy Boundary Test Fixtures:
 * Validates cross-role authorization matrix, edge cases (null merchant, unassigned courier,
 * customer vs merchant boundaries) and strict public-view PII redaction.
 */
@ExtendWith(MockitoExtension.class)
class TrackingPrivacyAndBoundaryTest {

  private DeliveryAuthorizationService authService;

  @Mock private TrackingParcelRepository parcelRepository;
  @Mock private TrackingEventRepository eventRepository;
  @Mock private TrackingStreamService streamService;
  private TrackingService trackingService;

  @BeforeEach
  void setUp() {
    authService = new DeliveryAuthorizationService();
    trackingService = new TrackingService(parcelRepository, eventRepository, streamService);
  }

  @Test
  @DisplayName("Privacy Boundary: Unassigned parcel (null courier) strictly denies all couriers")
  void unassignedParcelDeniesAllCouriers() {
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef("ZT-UNASSIGNED01");
    parcel.setCustomerSubject("cust-1");
    parcel.setCourierSubject(null); // Not yet assigned

    TestingAuthenticationToken courierToken = new TestingAuthenticationToken(
        "courier-john", "creds", List.of(new SimpleGrantedAuthority("ROLE_COURIER")));

    assertThat(authService.isAuthorized(courierToken, parcel)).isFalse();
    assertThat(authService.isAuthorized("courier-john", Set.of("ROLE_COURIER"), parcel)).isFalse();
  }

  @Test
  @DisplayName("Privacy Boundary: Customer-only parcel (null merchant) strictly denies all merchants")
  void customerOnlyParcelDeniesAllMerchants() {
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef("ZT-CUSTONLY01");
    parcel.setCustomerSubject("cust-1");
    parcel.setMerchantSubject(null); // Direct customer booking

    TestingAuthenticationToken merchantToken = new TestingAuthenticationToken(
        "merchant-acme", "creds", List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));

    assertThat(authService.isAuthorized(merchantToken, parcel)).isFalse();
    assertThat(authService.isAuthorized("merchant-acme", Set.of("ROLE_MERCHANT"), parcel)).isFalse();
  }

  @Test
  @DisplayName("Privacy Boundary: Foreign merchant cannot access another merchant's parcel")
  void foreignMerchantDeniedAccess() {
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef("ZT-MERCH01");
    parcel.setCustomerSubject("cust-1");
    parcel.setMerchantSubject("merchant-alpha");

    TestingAuthenticationToken foreignMerchantToken = new TestingAuthenticationToken(
        "merchant-beta", "creds", List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));

    assertThat(authService.isAuthorized(foreignMerchantToken, parcel)).isFalse();
  }

  @Test
  @DisplayName("Privacy Boundary: Empty or blank token subject is strictly rejected")
  void blankTokenSubjectDenied() {
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef("ZT-VALID01");
    parcel.setCustomerSubject("cust-1");

    TestingAuthenticationToken blankToken = new TestingAuthenticationToken(
        "   ", "creds", List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));

    assertThat(authService.isAuthorized(blankToken, parcel)).isFalse();
  }

  @Test
  @DisplayName("Public View Privacy: public tracking strictly redacts sender/recipient PII, address, and pricing")
  void publicTrackingResponseRedactsAllPiiAndPricing() {
    String ref = "ZT-PIITEST99";
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(ref);
    parcel.setSenderName("John Doe");
    parcel.setSenderPhone("+94771234567");
    parcel.setSenderAddress("123 Main Street, Apt 4B");
    parcel.setSenderCity("Colombo");
    parcel.setRecipientName("Jane Smith");
    parcel.setRecipientPhone("+94779876543");
    parcel.setRecipientAddress("456 Galle Road");
    parcel.setRecipientCity("Galle");
    parcel.setFee(new BigDecimal("1250.00"));
    parcel.setCurrency("LKR");
    parcel.setStatus(TrackingStatus.IN_TRANSIT);
    parcel.setCategory("Documents");
    parcel.setSize("SMALL");
    parcel.setEstimatedDays(2);
    parcel.setCreatedAt(Instant.parse("2026-11-10T10:00:00Z"));

    org.mockito.Mockito.when(parcelRepository.findByTrackingRef(ref)).thenReturn(Optional.of(parcel));
    org.mockito.Mockito.when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(ref)).thenReturn(List.of(
        new TrackingEvent(ref, TrackingStatus.CREATED, "Created", "Booked", Instant.parse("2026-11-10T10:00:00Z"))
    ));

    PublicTrackingResponse response = trackingService.getPublicTracking(ref);

    // City names are permitted operational descriptors
    assertThat(response.originCity()).isEqualTo("Colombo");
    assertThat(response.destinationCity()).isEqualTo("Galle");
    assertThat(response.status()).isEqualTo("In progress");
    assertThat(response.trackingRef()).isEqualTo(ref);

    // Assert that the public DTO class has NO getters/fields for sensitive personal data
    // (Reflection check confirming architectural privacy guarantee)
    Class<?> dtoClass = PublicTrackingResponse.class;
    assertThat(dtoClass.getDeclaredFields())
        .noneMatch(f -> f.getName().equalsIgnoreCase("senderName")
            || f.getName().equalsIgnoreCase("senderPhone")
            || f.getName().equalsIgnoreCase("senderAddress")
            || f.getName().equalsIgnoreCase("recipientName")
            || f.getName().equalsIgnoreCase("recipientPhone")
            || f.getName().equalsIgnoreCase("recipientAddress")
            || f.getName().equalsIgnoreCase("fee")
            || f.getName().equalsIgnoreCase("amount"));
  }
}
