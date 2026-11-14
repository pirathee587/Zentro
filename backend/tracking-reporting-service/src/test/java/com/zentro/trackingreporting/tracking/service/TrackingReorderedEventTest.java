package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
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
 * Tests verifying tracking event ordering, timeline reconstruction,
 * and out-of-order event arrival handling.
 */
@ExtendWith(MockitoExtension.class)
class TrackingReorderedEventTest {

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
  @DisplayName("Reordered event arrival: chronological history is preserved via occurredAt ordering")
  void outOfOrderEventsReconstructedChronologically() {
    Instant t1 = Instant.parse("2026-11-10T08:00:00Z"); // CREATED
    Instant t2 = Instant.parse("2026-11-10T09:30:00Z"); // PICKED_UP
    Instant t3 = Instant.parse("2026-11-10T12:00:00Z"); // IN_TRANSIT
    Instant t4 = Instant.parse("2026-11-10T15:00:00Z"); // DELIVERED

    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setStatus(TrackingStatus.DELIVERED);

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));

    // Even if events arrived out-of-order, findByTrackingRefOrderByOccurredAtAsc returns them sorted by occurredAt
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of(
        new TrackingEvent(trackingRef, TrackingStatus.CREATED, "Created", "Booked", t1),
        new TrackingEvent(trackingRef, TrackingStatus.PICKED_UP, "Picked Up", "Courier", t2),
        new TrackingEvent(trackingRef, TrackingStatus.IN_TRANSIT, "In Transit", "Hub", t3),
        new TrackingEvent(trackingRef, TrackingStatus.DELIVERED, "Delivered", "Recipient", t4)
    ));

    List<TrackingEventDto> events = trackingService.getTrackingEvents(trackingRef);

    assertThat(events).hasSize(4);
    assertThat(events.get(0).status()).isEqualTo("CREATED");
    assertThat(events.get(1).status()).isEqualTo("PICKED_UP");
    assertThat(events.get(2).status()).isEqualTo("IN_TRANSIT");
    assertThat(events.get(3).status()).isEqualTo("DELIVERED");

    // Timeline reflects correct chronological progression
    PublicTrackingResponse publicTracking = trackingService.getPublicTracking(trackingRef);
    assertThat(publicTracking.timeline()).hasSize(4);
    assertThat(publicTracking.timeline().get(0).title()).isEqualTo("Created");
    assertThat(publicTracking.timeline().get(3).title()).isEqualTo("Delivered");
    assertThat(publicTracking.timeline().get(3).status()).isEqualTo("completed");
  }

  @Test
  @DisplayName("Out-of-order event persists event record with actual occurredAt timestamp")
  void outOfOrderEventPersistsActualOccurredAt() {
    Instant earlierTime = Instant.parse("2026-11-10T09:00:00Z");
    UUID parcelId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setStatus(TrackingStatus.IN_TRANSIT);

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));
    when(eventRepository.save(any(TrackingEvent.class))).thenAnswer(i -> i.getArgument(0));

    // Record an earlier event (e.g. delayed PICKED_UP message arriving after IN_TRANSIT)
    TrackingEvent saved = trackingService.recordEvent(
        trackingRef,
        parcelId,
        TrackingStatus.PICKED_UP,
        "CREATED",
        "COURIER",
        "Picked Up",
        "Package collected",
        null,
        "Colombo",
        earlierTime,
        eventId
    );

    assertThat(saved.getOccurredAt()).isEqualTo(earlierTime);
    assertThat(saved.getStatus()).isEqualTo(TrackingStatus.PICKED_UP);
    verify(eventRepository).save(any(TrackingEvent.class));
  }
}
