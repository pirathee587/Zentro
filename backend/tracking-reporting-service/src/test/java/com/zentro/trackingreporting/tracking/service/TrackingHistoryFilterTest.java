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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * X02 Coverage:
 * Comprehensive tests covering all filter branches, status strings, query variations,
 * and boundary conditions for TrackingService.getCustomerDeliveries.
 */
@ExtendWith(MockitoExtension.class)
class TrackingHistoryFilterTest {

  @Mock private TrackingParcelRepository parcelRepository;
  @Mock private TrackingEventRepository eventRepository;
  @Mock private TrackingStreamService streamService;

  private TrackingService trackingService;

  @BeforeEach
  void setUp() {
    trackingService = new TrackingService(parcelRepository, eventRepository, streamService);
  }

  @Test
  @DisplayName("Status 'ALL' is treated as no status filter")
  void statusAllTreatedAsNoStatusFilter() {
    String subject = "user-subject-1";
    when(parcelRepository.findByCustomerSubject(eq(subject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(subject, 1, 10, "ALL", null);

    assertThat(response.total()).isEqualTo(0);
    verify(parcelRepository).findByCustomerSubject(eq(subject), any(Pageable.class));
  }

  @Test
  @DisplayName("Invalid status string safely returns empty Page without database error")
  void invalidStatusReturnsEmptyPage() {
    String subject = "user-subject-2";

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(subject, 1, 10, "NON_EXISTENT_STATUS", null);

    assertThat(response.total()).isEqualTo(0);
    assertThat(response.content()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"})
  @DisplayName("Valid status strings correctly query by status")
  void validStatusesQueryByStatus(String statusStr) {
    String subject = "user-subject-3";
    TrackingStatus expectedStatus = TrackingStatus.valueOf(statusStr);

    when(parcelRepository.findByCustomerSubjectAndStatus(eq(subject), eq(expectedStatus), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(subject, 1, 10, statusStr, null);

    assertThat(response.total()).isEqualTo(0);
    verify(parcelRepository).findByCustomerSubjectAndStatus(eq(subject), eq(expectedStatus), any(Pageable.class));
  }

  @Test
  @DisplayName("Empty or whitespace query string is ignored as no-query filter")
  void whitespaceQueryIgnored() {
    String subject = "user-subject-4";
    when(parcelRepository.findByCustomerSubject(eq(subject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(subject, 1, 10, null, "    ");

    assertThat(response.total()).isEqualTo(0);
    verify(parcelRepository).findByCustomerSubject(eq(subject), any(Pageable.class));
  }

  @Test
  @DisplayName("Pagination bounds: page 0 is clamped to page 1 (index 0), size clamped to 100 max")
  void paginationBoundsClamping() {
    String subject = "user-subject-5";
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

    when(parcelRepository.findByCustomerSubject(eq(subject), captor.capture()))
        .thenReturn(new PageImpl<>(List.of()));

    trackingService.getCustomerDeliveries(subject, 0, 500, null, null);

    Pageable pageable = captor.getValue();
    assertThat(pageable.getPageNumber()).isEqualTo(0);
    assertThat(pageable.getPageSize()).isEqualTo(100);
    assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  @Test
  @DisplayName("Combined search query with status trims keyword and passes to repository")
  void searchCombinedWithStatusTrimsQuery() {
    String subject = "user-subject-6";
    when(parcelRepository.searchByCustomerAndStatus(eq(subject), eq(TrackingStatus.IN_TRANSIT), eq("ZT-987"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(subject, 1, 10, "IN_TRANSIT", "  ZT-987  ");

    assertThat(response.total()).isEqualTo(0);
    verify(parcelRepository).searchByCustomerAndStatus(eq(subject), eq(TrackingStatus.IN_TRANSIT), eq("ZT-987"), any(Pageable.class));
  }

  @Test
  @DisplayName("Total spent sums fees correctly across multiple parcel rows")
  void totalSpentSumsFeesCorrectly() {
    String subject = "user-subject-7";
    TrackingParcel p1 = new TrackingParcel();
    p1.setTrackingRef("ZT-FEE01");
    p1.setCustomerSubject(subject);
    p1.setFee(new BigDecimal("1250.50"));
    p1.setStatus(TrackingStatus.DELIVERED);

    TrackingParcel p2 = new TrackingParcel();
    p2.setTrackingRef("ZT-FEE02");
    p2.setCustomerSubject(subject);
    p2.setFee(new BigDecimal("749.50"));
    p2.setStatus(TrackingStatus.CREATED);

    when(parcelRepository.findByCustomerSubject(eq(subject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(p1, p2)));

    CustomerDeliveryListResponse response = trackingService.getCustomerDeliveries(subject, 1, 10, null, null);

    assertThat(response.total()).isEqualTo(2);
    assertThat(response.totalSpent()).isEqualByComparingTo("2000.00");
    assertThat(response.formattedTotalSpent()).contains("2,000.00");
  }
}
