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
  @DisplayName("Characterize G04: recordEvent saves event but does not create TrackingParcel when absent")
  void recordEventDoesNotCreateTrackingParcelWhenAbsent() {
    String trackingRef = "ZT-UNINITIALIZED01";
    UUID parcelId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    // Absent from parcelRepository
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

    // Event is persisted
    assertThat(recorded).isNotNull();
    assertThat(recorded.getTrackingRef()).isEqualTo(trackingRef);
    assertThat(recorded.getStatus()).isEqualTo(TrackingStatus.CREATED);
    verify(eventRepository).save(any(TrackingEvent.class));

    // BUT parcelRepository.save was never called because recordEvent only updates existing parcels
    verify(parcelRepository, never()).save(any(TrackingParcel.class));

    // Attempting to read public tracking fails because no TrackingParcel was created
    assertThatThrownBy(() -> trackingService.getPublicTracking(trackingRef))
        .isInstanceOf(TrackingNotFoundException.class)
        .hasMessageContaining(trackingRef);
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
