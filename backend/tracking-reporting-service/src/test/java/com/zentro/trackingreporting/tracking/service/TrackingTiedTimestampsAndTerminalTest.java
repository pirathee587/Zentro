package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * G04/X02 Coverage:
 * Tests verifying handling of tied timestamps (identical occurredAt), terminal states
 * (DELIVERED and FAILED), deliveredAt preservation, and failureReason projection.
 */
@ExtendWith(MockitoExtension.class)
class TrackingTiedTimestampsAndTerminalTest {

  @Mock private TrackingParcelRepository parcelRepository;
  @Mock private TrackingEventRepository eventRepository;
  @Mock private TrackingStreamService streamService;

  private TrackingService trackingService;
  private final String trackingRef = "ZT-7K3QX9M2PA";

  @BeforeEach
  void setUp() {
    trackingService = new TrackingService(parcelRepository, eventRepository, streamService);
  }

  @Test
  @DisplayName("Tied timestamps: multiple events with identical occurredAt are both preserved in timeline")
  void tiedTimestampsPreservedInTimeline() {
    Instant tiedTime = Instant.parse("2026-11-10T12:00:00Z");

    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setStatus(TrackingStatus.IN_TRANSIT);

    TrackingEvent ev1 = new TrackingEvent(trackingRef, TrackingStatus.PICKED_UP, "Courier Assigned", "Driver accepted", tiedTime);
    TrackingEvent ev2 = new TrackingEvent(trackingRef, TrackingStatus.IN_TRANSIT, "Departed Facility", "En route", tiedTime);

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of(ev1, ev2));

    PublicTrackingResponse response = trackingService.getPublicTracking(trackingRef);

    assertThat(response.events()).hasSize(2);
    assertThat(response.timeline()).hasSize(2);
    assertThat(response.events().get(0).occurredAt()).isEqualTo(tiedTime);
    assertThat(response.events().get(1).occurredAt()).isEqualTo(tiedTime);
    assertThat(response.timeline().get(0).title()).isEqualTo("Courier Assigned");
    assertThat(response.timeline().get(1).title()).isEqualTo("Departed Facility");
  }

  @Test
  @DisplayName("Terminal event: DELIVERED sets deliveredAt timestamp and preserves it on parcel")
  void terminalEventDeliveredSetsDeliveredAt() {
    Instant deliveryTime = Instant.parse("2026-11-10T16:30:00Z");
    UUID parcelId = UUID.randomUUID();

    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setStatus(TrackingStatus.OUT_FOR_DELIVERY);
    parcel.setDeliveredAt(null);

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));
    when(eventRepository.save(any(TrackingEvent.class))).thenAnswer(i -> i.getArgument(0));

    trackingService.recordEvent(
        trackingRef,
        parcelId,
        TrackingStatus.DELIVERED,
        "OUT_FOR_DELIVERY",
        "COURIER",
        "Delivered",
        "Handed to recipient",
        null,
        "Colombo",
        deliveryTime,
        UUID.randomUUID()
    );

    ArgumentCaptor<TrackingParcel> captor = ArgumentCaptor.forClass(TrackingParcel.class);
    verify(parcelRepository).save(captor.capture());

    TrackingParcel savedParcel = captor.getValue();
    assertThat(savedParcel.getStatus()).isEqualTo(TrackingStatus.DELIVERED);
    assertThat(savedParcel.getDeliveredAt()).isNotNull();
  }

  @Test
  @DisplayName("Terminal event: FAILED projects failureReason and sets milestone status to 'failed'")
  void terminalEventFailedProjectsFailureReason() {
    Instant failureTime = Instant.parse("2026-11-10T17:00:00Z");
    UUID parcelId = UUID.randomUUID();
    String reason = "Recipient address incorrect, unreachable phone";

    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setStatus(TrackingStatus.OUT_FOR_DELIVERY);

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));
    when(eventRepository.save(any(TrackingEvent.class))).thenAnswer(i -> i.getArgument(0));

    trackingService.recordEvent(
        trackingRef,
        parcelId,
        TrackingStatus.FAILED,
        "OUT_FOR_DELIVERY",
        "COURIER",
        "Delivery Attempt Failed",
        reason,
        reason,
        "Colombo",
        failureTime,
        UUID.randomUUID()
    );

    ArgumentCaptor<TrackingParcel> captor = ArgumentCaptor.forClass(TrackingParcel.class);
    verify(parcelRepository).save(captor.capture());

    TrackingParcel savedParcel = captor.getValue();
    assertThat(savedParcel.getStatus()).isEqualTo(TrackingStatus.FAILED);
    assertThat(savedParcel.getFailureReason()).isEqualTo(reason);

    // Verify timeline formatting for FAILED step
    TrackingEvent failedEvent = new TrackingEvent(trackingRef, TrackingStatus.FAILED, "Delivery Failed", reason, failureTime);
    failedEvent.setFailureReason(reason);
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of(failedEvent));

    PublicTrackingResponse response = trackingService.getPublicTracking(trackingRef);
    assertThat(response.timeline().get(0).status()).isEqualTo("failed");
    assertThat(response.timeline().get(0).failureReason()).isEqualTo(reason);
  }
}
