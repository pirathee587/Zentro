package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.tracking.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * X02 Verification:
 * Verifies that TrackingService.getCustomerDeliveries correctly falls back to
 * customerOrMerchant query when Spring Data returns an empty page from findByCustomerSubject,
 * allowing merchant-owned records to be retrieved while strictly maintaining subject scoping.
 */
@ExtendWith(MockitoExtension.class)
class MerchantHistorySubjectScopingTest {

  @Mock private TrackingParcelRepository parcelRepository;
  @Mock private TrackingEventRepository eventRepository;
  @Mock private TrackingStreamService streamService;

  private TrackingService trackingService;

  @BeforeEach
  void setUp() {
    trackingService = new TrackingService(parcelRepository, eventRepository, streamService);
  }

  @Test
  @DisplayName("Fixed X02: Empty customer page successfully falls back to customerOrMerchant query for merchant records")
  void emptyCustomerPageFallsBackToMerchantQuery() {
    String merchantSubject = "merchant-subject-xyz";

    TrackingParcel merchantParcel = new TrackingParcel();
    merchantParcel.setTrackingRef("ZT-MERCHANT01");
    merchantParcel.setCustomerSubject("different-customer-subject");
    merchantParcel.setMerchantSubject(merchantSubject);
    merchantParcel.setStatus(TrackingStatus.CREATED);
    merchantParcel.setFee(new BigDecimal("1000.00"));

    // findByCustomerSubject returns empty PageImpl
    when(parcelRepository.findByCustomerSubject(eq(merchantSubject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    // Fallback retrieves the merchant-owned parcel
    when(parcelRepository.findByCustomerOrMerchantSubject(eq(merchantSubject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(merchantParcel)));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(merchantSubject, 1, 10, null, null);

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.content()).hasSize(1);
    assertThat(response.content().get(0).trackingRef()).isEqualTo("ZT-MERCHANT01");
    assertThat(response.totalSpent()).isEqualByComparingTo("1000.00");
    verify(parcelRepository).findByCustomerOrMerchantSubject(eq(merchantSubject), any(Pageable.class));
  }

  @Test
  @DisplayName("Fixed X02: Empty customer page with status filter falls back to merchant status query")
  void emptyCustomerPageWithStatusFallsBackToMerchantStatusQuery() {
    String merchantSubject = "merchant-subject-xyz";

    TrackingParcel merchantParcel = new TrackingParcel();
    merchantParcel.setTrackingRef("ZT-MERCHANT02");
    merchantParcel.setMerchantSubject(merchantSubject);
    merchantParcel.setStatus(TrackingStatus.IN_TRANSIT);
    merchantParcel.setFee(new BigDecimal("1500.00"));

    when(parcelRepository.findByCustomerSubjectAndStatus(eq(merchantSubject), eq(TrackingStatus.IN_TRANSIT), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));
    when(parcelRepository.findByCustomerOrMerchantSubjectAndStatus(eq(merchantSubject), eq(TrackingStatus.IN_TRANSIT), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(merchantParcel)));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(merchantSubject, 1, 10, "IN_TRANSIT", null);

    assertThat(response.total()).isEqualTo(1);
    assertThat(response.content().get(0).trackingRef()).isEqualTo("ZT-MERCHANT02");
    verify(parcelRepository).findByCustomerOrMerchantSubjectAndStatus(eq(merchantSubject), eq(TrackingStatus.IN_TRANSIT), any(Pageable.class));
  }
}
