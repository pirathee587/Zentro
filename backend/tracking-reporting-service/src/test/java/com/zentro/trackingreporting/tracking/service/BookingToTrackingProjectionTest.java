package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.tracking.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * End-to-end service scenario test verifying complete booking-to-tracking projection lifecycle:
 * Booking creation -> Tracking projection -> Public tracking -> Owner history ->
 * Courier assignment -> Status transitions -> Terminal DELIVERED completion.
 */
@ExtendWith(MockitoExtension.class)
class BookingToTrackingProjectionTest {

  @Mock private TrackingParcelRepository parcelRepository;
  @Mock private TrackingEventRepository eventRepository;
  @Mock private TrackingStreamService streamService;

  private TrackingService trackingService;
  private final String trackingRef = "ZT-7K3QX9M2PA";
  private final UUID parcelId = UUID.randomUUID();
  private final String customerSubject = "customer-user-42";
  private final String merchantSubject = "merchant-corp-99";

  @BeforeEach
  void setUp() {
    trackingService = new TrackingService(parcelRepository, eventRepository, streamService);
  }

  @Test
  @DisplayName("Complete booking-to-tracking projection lifecycle through delivery completion")
  void completeBookingToTrackingLifecycle() {
    Instant t0 = Instant.parse("2026-11-10T08:00:00Z");
    Instant t1 = Instant.parse("2026-11-10T10:00:00Z");
    Instant t2 = Instant.parse("2026-11-10T14:30:00Z");

    // Phase 1: Booking initialized
    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.empty());
    when(parcelRepository.save(any(TrackingParcel.class))).thenAnswer(i -> i.getArgument(0));
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of());
    when(eventRepository.save(any(TrackingEvent.class))).thenAnswer(i -> i.getArgument(0));

    TrackingParcel initializedParcel = trackingService.initializeTrackingParcel(
        parcelId,
        trackingRef,
        customerSubject,
        merchantSubject,
        "Electronics",
        "Colombo",
        "Galle",
        new BigDecimal("1850.00"),
        "LKR",
        2
    );

    assertThat(initializedParcel).isNotNull();
    assertThat(initializedParcel.getTrackingRef()).isEqualTo(trackingRef);
    assertThat(initializedParcel.getStatus()).isEqualTo(TrackingStatus.CREATED);
    assertThat(initializedParcel.getFee()).isEqualByComparingTo("1850.00");

    // Phase 2: Public tracking is active and timeline is constructed
    TrackingEvent createdEvent = new TrackingEvent(trackingRef, TrackingStatus.CREATED, "Created", "Delivery order registered", t0);
    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(initializedParcel));
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of(createdEvent));

    PublicTrackingResponse publicTracking = trackingService.getPublicTracking(trackingRef);
    assertThat(publicTracking.trackingRef()).isEqualTo(trackingRef);
    assertThat(publicTracking.status()).isEqualTo("Created");
    assertThat(publicTracking.originCity()).isEqualTo("Colombo");
    assertThat(publicTracking.destinationCity()).isEqualTo("Galle");
    assertThat(publicTracking.timeline()).hasSize(1);
    assertThat(publicTracking.timeline().get(0).status()).isEqualTo("current");

    // Phase 3: Customer history includes delivery and computes spend
    when(parcelRepository.findByCustomerSubject(eq(customerSubject), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(initializedParcel)));

    CustomerDeliveryListResponse history = trackingService.getCustomerDeliveries(customerSubject, 1, 10, null, null);
    assertThat(history.total()).isEqualTo(1);
    assertThat(history.content().get(0).trackingRef()).isEqualTo(trackingRef);
    assertThat(history.totalSpent()).isEqualByComparingTo("1850.00");

    // Phase 4: Courier assigned
    when(parcelRepository.findByParcelId(parcelId)).thenReturn(Optional.of(initializedParcel));
    TrackingParcel assigned = trackingService.updateCourierAssignment(parcelId, null, "courier-driver-7", "ASSIGNED");
    assertThat(assigned.getCourierSubject()).isEqualTo("courier-driver-7");

    // Phase 5: Terminal event DELIVERED
    TrackingEvent deliveredEvent = new TrackingEvent(trackingRef, TrackingStatus.DELIVERED, "Delivered", "Delivered to recipient", t2);
    List<TrackingEvent> allEvents = new ArrayList<>(List.of(createdEvent, deliveredEvent));
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(allEvents);

    trackingService.recordEvent(
        trackingRef,
        parcelId,
        TrackingStatus.DELIVERED,
        "OUT_FOR_DELIVERY",
        "COURIER",
        "Delivered",
        "Package handed to recipient",
        null,
        "Galle",
        t2,
        UUID.randomUUID()
    );

    ArgumentCaptor<TrackingParcel> parcelCaptor = ArgumentCaptor.forClass(TrackingParcel.class);
    verify(parcelRepository, org.mockito.Mockito.atLeastOnce()).save(parcelCaptor.capture());
    TrackingParcel finalParcel = parcelCaptor.getValue();
    assertThat(finalParcel.getStatus()).isEqualTo(TrackingStatus.DELIVERED);
    assertThat(finalParcel.getDeliveredAt()).isNotNull();

    // Verify public tracking reflects delivered state
    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(finalParcel));
    PublicTrackingResponse finalPublic = trackingService.getPublicTracking(trackingRef);
    assertThat(finalPublic.rawStatus()).isEqualTo("DELIVERED");
    assertThat(finalPublic.status()).isEqualTo("Delivered");
    assertThat(finalPublic.deliveredAt()).isNotNull();
    assertThat(finalPublic.timeline()).hasSize(2);
    assertThat(finalPublic.timeline().get(1).status()).isEqualTo("completed");
  }
}
