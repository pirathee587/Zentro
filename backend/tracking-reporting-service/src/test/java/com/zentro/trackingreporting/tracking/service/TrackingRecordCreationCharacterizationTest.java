package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.exception.TrackingNotFoundException;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * G04 Characterization Test:
 * Characterizes the existing tracking record creation gap where TrackingService.recordEvent
 * updates tracking_parcel ONLY when the record already exists, leaving public tracking
 * unreadable (TrackingNotFoundException) when only events arrive.
 */
@ExtendWith(MockitoExtension.class)
class TrackingRecordCreationCharacterizationTest {

  @Mock private TrackingParcelRepository parcelRepository;
  @Mock private TrackingEventRepository eventRepository;
  @Mock private TrackingStreamService streamService;

  private TrackingService trackingService;

  @BeforeEach
  void setUp() {
    trackingService = new TrackingService(parcelRepository, eventRepository, streamService);
  }

  @Test
  @DisplayName("G04 resolution: recordEvent initializes minimal TrackingParcel when CREATED event arrives")
  void recordEventCreatesMinimalTrackingParcelWhenCreatedEventArrives() {
    String trackingRef = "ZT-7K3QX9M2PA";
    UUID parcelId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.empty());
    when(eventRepository.save(any(TrackingEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TrackingEvent recorded = trackingService.recordEvent(
        trackingRef,
        parcelId,
        TrackingStatus.CREATED,
        null,
        "CUSTOMER",
        "Parcel Created",
        "Initial booking event",
        null,
        "Colombo",
        Instant.now(),
        eventId
    );

    assertThat(recorded).isNotNull();
    assertThat(recorded.getTrackingRef()).isEqualTo(trackingRef);
    assertThat(recorded.getStatus()).isEqualTo(TrackingStatus.CREATED);
    verify(eventRepository).save(any(TrackingEvent.class));

    // Verifies parcel was initialized and saved
    org.mockito.ArgumentCaptor<TrackingParcel> captor = org.mockito.ArgumentCaptor.forClass(TrackingParcel.class);
    verify(parcelRepository).save(captor.capture());
    TrackingParcel saved = captor.getValue();
    assertThat(saved.getTrackingRef()).isEqualTo(trackingRef);
    assertThat(saved.getParcelId()).isEqualTo(parcelId);
    assertThat(saved.getStatus()).isEqualTo(TrackingStatus.CREATED);
  }

  @Test
  @DisplayName("G04 resolution: initializeTrackingParcel creates full projection and initial CREATED event")
  void initializeTrackingParcelCreatesFullProjectionAndInitialEvent() {
    String trackingRef = "ZT-7K3QX9M2PA";
    UUID parcelId = UUID.randomUUID();

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.empty());
    when(parcelRepository.save(any(TrackingParcel.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(java.util.List.of());
    when(eventRepository.save(any(TrackingEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TrackingParcel initialized = trackingService.initializeTrackingParcel(
        parcelId,
        trackingRef,
        "customer-123",
        "merchant-456",
        "Electronics",
        "Colombo",
        "Kandy",
        new java.math.BigDecimal("1250.00"),
        "LKR",
        2
    );

    assertThat(initialized).isNotNull();
    assertThat(initialized.getTrackingRef()).isEqualTo(trackingRef);
    assertThat(initialized.getCustomerSubject()).isEqualTo("customer-123");
    assertThat(initialized.getMerchantSubject()).isEqualTo("merchant-456");
    assertThat(initialized.getSenderCity()).isEqualTo("Colombo");
    assertThat(initialized.getRecipientCity()).isEqualTo("Kandy");
    assertThat(initialized.getFee()).isEqualByComparingTo("1250.00");
    assertThat(initialized.getStatus()).isEqualTo(TrackingStatus.CREATED);

    verify(parcelRepository, org.mockito.Mockito.atLeastOnce()).save(any(TrackingParcel.class));
    verify(eventRepository).save(any(TrackingEvent.class));
  }

  @Test
  @DisplayName("Characterize G04: getParcel throws TrackingNotFoundException when TrackingParcel was never saved")
  void getParcelThrowsNotFoundWhenParcelWasNeverSaved() {
    String trackingRef = "ZT-NOSAVEDPARCEL";
    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> trackingService.getParcel(trackingRef))
        .isInstanceOf(TrackingNotFoundException.class);
  }
}
