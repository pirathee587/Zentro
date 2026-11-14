package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
 * X02 Reproduction Test:
 * Reproduces the bug where TrackingService.getCustomerDeliveries checks 'if (paged == null)'
 * to fallback to customerOrMerchant query, but Spring Data JPA repositories never return null
 * (they return Page.empty() / empty PageImpl).
 * As a result, merchant-only records are never fetched through the fallback.
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
  @DisplayName("Reproduce X02: Spring Data returns empty Page (not null), bypassing the customerOrMerchant fallback")
  void emptyPageBypassesCustomerOrMerchantFallback() {
    String merchantSubject = "merchant-subject-xyz";

    TrackingParcel merchantParcel = new TrackingParcel();
    merchantParcel.setTrackingRef("ZT-MERCHANT01");
    merchantParcel.setCustomerSubject("different-customer-subject");
    merchantParcel.setMerchantSubject(merchantSubject);
    merchantParcel.setStatus(TrackingStatus.CREATED);
    merchantParcel.setFee(new BigDecimal("1000.00"));

    // Spring Data repository behavior: returns empty PageImpl, NOT null
    when(parcelRepository.findByCustomerSubject(eq(merchantSubject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    // Even if parcelRepository.findByCustomerOrMerchantSubject is stubbed:
    lenient().when(parcelRepository.findByCustomerOrMerchantSubject(eq(merchantSubject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(merchantParcel)));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(merchantSubject, 1, 10, null, null);

    // Characterizes the bug: Because paged is an empty PageImpl (not null), the fallback is bypassed and 0 items are returned!
    assertThat(response.total()).isEqualTo(0);
    assertThat(response.content()).isEmpty();
    verify(parcelRepository, never()).findByCustomerOrMerchantSubject(eq(merchantSubject), any(Pageable.class));
  }

  @Test
  @DisplayName("Reproduce X02 with status filter: empty page bypasses status fallback")
  void emptyPageWithStatusFilterBypassesFallback() {
    String merchantSubject = "merchant-subject-xyz";

    when(parcelRepository.findByCustomerSubjectAndStatus(eq(merchantSubject), eq(TrackingStatus.IN_TRANSIT), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(merchantSubject, 1, 10, "IN_TRANSIT", null);

    assertThat(response.total()).isEqualTo(0);
    verify(parcelRepository, never()).findByCustomerOrMerchantSubjectAndStatus(eq(merchantSubject), eq(TrackingStatus.IN_TRANSIT), any(Pageable.class));
  }
}
