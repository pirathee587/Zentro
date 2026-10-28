package com.zentro.parcelpricing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.parcelpricing.domain.DestinationType;
import com.zentro.parcelpricing.domain.ParcelBooking;
import com.zentro.parcelpricing.domain.ParcelSize;
import com.zentro.parcelpricing.domain.ParcelStatus;
import com.zentro.parcelpricing.dto.CreateParcelBookingRequest;
import com.zentro.parcelpricing.dto.FeeEstimateRequest;
import com.zentro.parcelpricing.dto.FeeEstimateResponse;
import com.zentro.parcelpricing.error.IdempotencyConflictException;
import com.zentro.parcelpricing.error.PricingUnavailableException;
import com.zentro.parcelpricing.service.ParcelBookingService.BookingResult;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;

@ExtendWith(MockitoExtension.class)
class ParcelBookingServiceTest {
  private static final String CUSTOMER = "customer-sub-1";

  @Mock PricingService pricingService;
  @Mock ParcelBookingRepository repository;
  @Mock TrackingReferenceGenerator trackingReferenceGenerator;

  ParcelBookingService service;

  @BeforeEach
  void setUp() {
    service = new ParcelBookingService(pricingService, repository, trackingReferenceGenerator);
  }

  @Test
  @DisplayName("Successful booking is priced first and saved with status CREATED and the quoted fee")
  void successfulBookingIsSaved() {
    stubPrice("1072.00", 3);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-ABCDEFGH12");
    when(repository.insert(any())).thenAnswer(inv -> saved(inv.getArgument(0)));

    BookingResult result = service.book(CUSTOMER, request("0771234567"), null);

    assertThat(result.created()).isTrue();
    assertThat(result.booking().trackingRef()).isEqualTo("ZT-ABCDEFGH12");
    assertThat(result.booking().status()).isEqualTo(ParcelStatus.CREATED);
    assertThat(result.booking().fee()).isEqualByComparingTo("1072.00");
    assertThat(result.booking().currency()).isEqualTo("LKR");
    assertThat(result.booking().estimatedDays()).isEqualTo(3);

    ArgumentCaptor<FeeEstimateRequest> priced = ArgumentCaptor.forClass(FeeEstimateRequest.class);
    verify(pricingService).estimateFee(priced.capture());
    assertThat(priced.getValue().pickupCity()).isEqualTo("Colombo");
    assertThat(priced.getValue().deliveryCity()).isEqualTo("Kandy");
    assertThat(priced.getValue().destinationType()).isEqualTo(DestinationType.OTHER_DISTRICT);
    assertThat(priced.getValue().parcelSize()).isEqualTo(ParcelSize.SMALL);
    assertThat(priced.getValue().weightKg()).isEqualByComparingTo("2.00");
    assertThat(priced.getValue().parcelCategory()).isEqualTo("Small Box");
    assertThat(priced.getValue().currency()).isEqualTo("LKR");
    assertThat(priced.getValue().distanceKm()).isNull();

    ArgumentCaptor<ParcelBooking> stored = ArgumentCaptor.forClass(ParcelBooking.class);
    verify(repository).insert(stored.capture());
    assertThat(stored.getValue().customerSubject()).isEqualTo(CUSTOMER);
    assertThat(stored.getValue().status()).isEqualTo(ParcelStatus.CREATED);
    assertThat(stored.getValue().idempotencyKey()).isNull();
    assertThat(stored.getValue().requestHash()).isNull();
  }

  @Test
  @DisplayName("Phone separators are removed and text is trimmed before saving")
  void inputIsNormalized() {
    stubPrice("550.00", 2);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-ABCDEFGH12");
    when(repository.insert(any())).thenAnswer(inv -> saved(inv.getArgument(0)));

    service.book(CUSTOMER, request("077 123-4567"), null);

    ArgumentCaptor<ParcelBooking> stored = ArgumentCaptor.forClass(ParcelBooking.class);
    verify(repository).insert(stored.capture());
    assertThat(stored.getValue().sender().phone()).isEqualTo("0771234567");
    assertThat(stored.getValue().sender().name()).isEqualTo("Nimal Perera");
  }

  @Test
  @DisplayName("Pricing rejecting the data (IllegalArgumentException) saves nothing")
  void pricingRejectionSavesNothing() {
    when(pricingService.estimateFee(any())).thenThrow(new IllegalArgumentException("Unsupported currency"));

    assertThatThrownBy(() -> service.book(CUSTOMER, request("0771234567"), null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(repository, never()).insert(any());
  }

  @Test
  @DisplayName("Pricing runtime failure becomes PricingUnavailableException and saves nothing")
  void pricingFailureSavesNothing() {
    when(pricingService.estimateFee(any())).thenThrow(new QueryTimeoutException("db down"));

    assertThatThrownBy(() -> service.book(CUSTOMER, request("0771234567"), "key-1"))
        .isInstanceOf(PricingUnavailableException.class);
    verify(repository, never()).insert(any());
  }

  @Test
  @DisplayName("Pricing returning no fee is treated as unavailable and saves nothing")
  void missingFeeSavesNothing() {
    when(pricingService.estimateFee(any())).thenReturn(null);

    assertThatThrownBy(() -> service.book(CUSTOMER, request("0771234567"), null))
        .isInstanceOf(PricingUnavailableException.class);
    verify(repository, never()).insert(any());
  }

  @Test
  @DisplayName("Tracking reference collision is retried with a new reference")
  void trackingRefCollisionIsRetried() {
    stubPrice("550.00", 2);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-AAAAAAAAAA", "ZT-BBBBBBBBBB");
    when(repository.insert(any()))
        .thenThrow(trackingRefCollision())
        .thenAnswer(inv -> saved(inv.getArgument(0)));

    BookingResult result = service.book(CUSTOMER, request("0771234567"), null);

    assertThat(result.booking().trackingRef()).isEqualTo("ZT-BBBBBBBBBB");
    verify(repository, times(2)).insert(any());
    verify(pricingService, times(1)).estimateFee(any());
  }

  @Test
  @DisplayName("Tracking reference collisions stop after three attempts")
  void trackingRefCollisionGivesUpAfterThreeAttempts() {
    stubPrice("550.00", 2);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-AAAAAAAAAA");
    when(repository.insert(any())).thenThrow(trackingRefCollision());

    assertThatThrownBy(() -> service.book(CUSTOMER, request("0771234567"), null))
        .isInstanceOf(DuplicateKeyException.class);
    verify(repository, times(ParcelBookingService.MAX_TRACKING_REF_ATTEMPTS)).insert(any());
  }

  @Test
  @DisplayName("Same Idempotency-Key and same body replays the original booking without pricing or saving")
  void idempotentReplayReturnsOriginal() {
    stubPrice("550.00", 2);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-ABCDEFGH12");
    when(repository.findByCustomerAndIdempotencyKey(CUSTOMER, "key-1")).thenReturn(Optional.empty());
    when(repository.insert(any())).thenAnswer(inv -> saved(inv.getArgument(0)));
    BookingResult first = service.book(CUSTOMER, request("0771234567"), "key-1");

    ArgumentCaptor<ParcelBooking> stored = ArgumentCaptor.forClass(ParcelBooking.class);
    verify(repository).insert(stored.capture());
    ParcelBooking original = saved(stored.getValue());
    when(repository.findByCustomerAndIdempotencyKey(CUSTOMER, "key-1")).thenReturn(Optional.of(original));

    // Same booking, only formatting differences in the phone number.
    BookingResult second = service.book(CUSTOMER, request("077-123 4567"), "key-1");

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.booking().trackingRef()).isEqualTo(first.booking().trackingRef());
    verify(repository, times(1)).insert(any());
    verify(pricingService, times(1)).estimateFee(any());
  }

  @Test
  @DisplayName("Same Idempotency-Key with a different body returns a conflict")
  void idempotencyKeyReuseWithDifferentBodyConflicts() {
    ParcelBooking existing = saved(draft("someone-elses-hash", "key-1"));
    when(repository.findByCustomerAndIdempotencyKey(CUSTOMER, "key-1")).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.book(CUSTOMER, request("0771234567"), "key-1"))
        .isInstanceOf(IdempotencyConflictException.class);
    verify(pricingService, never()).estimateFee(any());
    verify(repository, never()).insert(any());
  }

  @Test
  @DisplayName("Concurrent duplicate with the same key replays the winning booking")
  void concurrentDuplicateIsReplayed() {
    stubPrice("550.00", 2);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-ABCDEFGH12");
    ArgumentCaptor<ParcelBooking> attempted = ArgumentCaptor.forClass(ParcelBooking.class);
    when(repository.insert(attempted.capture())).thenThrow(new DuplicateKeyException(
        "duplicate key value violates unique constraint \"uq_parcel_customer_idempotency\""));

    // After the failed insert, the other request's row is visible.
    when(repository.findByCustomerAndIdempotencyKey(eq(CUSTOMER), eq("key-1")))
        .thenReturn(Optional.empty())
        .thenAnswer(inv -> Optional.of(saved(attempted.getValue().withTrackingRef("ZT-WINNER0001"))));

    BookingResult result = service.book(CUSTOMER, request("0771234567"), "key-1");

    assertThat(result.created()).isFalse();
    assertThat(result.booking().trackingRef()).isEqualTo("ZT-WINNER0001");
  }

  @Test
  @DisplayName("Different customers can use the same Idempotency-Key independently")
  void idempotencyKeyIsScopedToCustomer() {
    stubPrice("550.00", 2);
    when(trackingReferenceGenerator.next()).thenReturn("ZT-ABCDEFGH12");
    when(repository.findByCustomerAndIdempotencyKey(anyString(), anyString())).thenReturn(Optional.empty());
    when(repository.insert(any())).thenAnswer(inv -> saved(inv.getArgument(0)));

    service.book("customer-a", request("0771234567"), "shared-key");
    service.book("customer-b", request("0771234567"), "shared-key");

    verify(repository).findByCustomerAndIdempotencyKey("customer-a", "shared-key");
    verify(repository).findByCustomerAndIdempotencyKey("customer-b", "shared-key");
    verify(repository, times(2)).insert(any());
  }

  private void stubPrice(String fee, int days) {
    when(pricingService.estimateFee(any())).thenReturn(new FeeEstimateResponse(
        new BigDecimal(fee), "LKR " + fee, "LKR", days,
        new FeeEstimateResponse.Breakdown(new BigDecimal("550.00"), BigDecimal.ZERO, null, null)));
  }

  private static DuplicateKeyException trackingRefCollision() {
    return new DuplicateKeyException("duplicate key value violates unique constraint \"uq_parcel_tracking_ref\"");
  }

  static CreateParcelBookingRequest request(String senderPhone) {
    return new CreateParcelBookingRequest(
        new CreateParcelBookingRequest.Contact(" Nimal Perera ", senderPhone, "12 Galle Road, Colombo 03", "Colombo"),
        new CreateParcelBookingRequest.Contact("Kamala Silva", "+94771234567", "45 Temple Street, Kandy", "Kandy"),
        new CreateParcelBookingRequest.ParcelDetails(new BigDecimal("2"), "Small Box", ParcelSize.SMALL, null),
        DestinationType.OTHER_DISTRICT,
        null,
        null);
  }

  private static ParcelBooking draft(String requestHash, String idempotencyKey) {
    return new ParcelBooking(null, "ZT-EXISTING01", CUSTOMER,
        new ParcelBooking.Contact("A", "0771234567", "12 Galle Road", null),
        new ParcelBooking.Contact("B", "0771234567", "45 Temple Street", null),
        new BigDecimal("2.00"), ParcelSize.SMALL, "Small Box", null, DestinationType.SAME_CITY, false, null,
        new BigDecimal("670.00"), "LKR", 2, ParcelStatus.CREATED, idempotencyKey, requestHash, null, null);
  }

  private static ParcelBooking saved(ParcelBooking draft) {
    OffsetDateTime now = OffsetDateTime.now();
    return new ParcelBooking(UUID.randomUUID(), draft.trackingRef(), draft.customerSubject(), draft.sender(),
        draft.recipient(), draft.weightKg(), draft.size(), draft.category(), draft.description(),
        draft.destinationType(), draft.express(), draft.notes(), draft.fee(), draft.currency(),
        draft.estimatedDays(), draft.status(), draft.idempotencyKey(), draft.requestHash(), now, now);
  }
}
