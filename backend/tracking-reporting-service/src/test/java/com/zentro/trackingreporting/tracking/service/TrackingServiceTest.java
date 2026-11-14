package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.exception.TrackingNotFoundException;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class TrackingServiceTest {

  @Mock private TrackingParcelRepository parcelRepository;
  @Mock private TrackingEventRepository eventRepository;
  @Mock private TrackingStreamService streamService;

  private TrackingService trackingService;

  @BeforeEach
  void setUp() {
    trackingService = new TrackingService(parcelRepository, eventRepository, streamService);
  }

  @Test
  @DisplayName("getPublicTracking returns public view and excludes private personal data")
  void getPublicTrackingExcludesPrivateData() {
    String trackingRef = "ZT-ABCDEFGH12";
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setSenderName("Secret Sender");
    parcel.setSenderPhone("0771234567");
    parcel.setSenderAddress("12 Top Secret Road");
    parcel.setSenderCity("Colombo");
    parcel.setRecipientName("Secret Recipient");
    parcel.setRecipientPhone("0777654321");
    parcel.setRecipientAddress("99 Hidden Street");
    parcel.setRecipientCity("Kandy");
    parcel.setFee(new BigDecimal("1500.00"));
    parcel.setStatus(TrackingStatus.IN_TRANSIT);
    parcel.setCategory("Parcel");
    parcel.setSize("MEDIUM");

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of(
        new TrackingEvent(trackingRef, TrackingStatus.CREATED, "Created", "Booked", Instant.parse("2026-11-04T10:00:00Z")),
        new TrackingEvent(trackingRef, TrackingStatus.IN_TRANSIT, "In Transit", "Hub", Instant.parse("2026-11-04T12:00:00Z"))
    ));

    PublicTrackingResponse response = trackingService.getPublicTracking(trackingRef);

    assertThat(response.trackingRef()).isEqualTo(trackingRef);
    assertThat(response.status()).isEqualTo("In progress");
    assertThat(response.rawStatus()).isEqualTo("IN_TRANSIT");
    assertThat(response.originCity()).isEqualTo("Colombo");
    assertThat(response.destinationCity()).isEqualTo("Kandy");
    assertThat(response.events()).hasSize(2);
    assertThat(response.timeline()).hasSize(2);

    // Verify chronological order: oldest to newest
    assertThat(response.events().get(0).occurredAt()).isBefore(response.events().get(1).occurredAt());
  }

  @Test
  @DisplayName("getPublicTracking throws TrackingNotFoundException for unknown reference")
  void getPublicTrackingThrowsNotFound() {
    when(parcelRepository.findByTrackingRef("ZT-UNKNOWN999")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> trackingService.getPublicTracking("ZT-UNKNOWN999"))
        .isInstanceOf(TrackingNotFoundException.class)
        .hasMessageContaining("ZT-UNKNOWN999");
  }

  @Test
  @DisplayName("getTrackingEvents returns chronological events in ascending order")
  void getTrackingEventsChronologicalOrder() {
    String trackingRef = "ZT-ORDER12345";
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setStatus(TrackingStatus.DELIVERED);

    Instant t1 = Instant.parse("2026-11-04T08:00:00Z");
    Instant t2 = Instant.parse("2026-11-04T11:00:00Z");
    Instant t3 = Instant.parse("2026-11-04T14:00:00Z");

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of(
        new TrackingEvent(trackingRef, TrackingStatus.CREATED, "Created", "Booked", t1),
        new TrackingEvent(trackingRef, TrackingStatus.PICKED_UP, "Picked Up", "Courier", t2),
        new TrackingEvent(trackingRef, TrackingStatus.DELIVERED, "Delivered", "Completed", t3)
    ));

    List<TrackingEventDto> events = trackingService.getTrackingEvents(trackingRef);

    assertThat(events).hasSize(3);
    assertThat(events.get(0).occurredAt()).isEqualTo(t1);
    assertThat(events.get(1).occurredAt()).isEqualTo(t2);
    assertThat(events.get(2).occurredAt()).isEqualTo(t3);
  }

  @Test
  @DisplayName("getCustomerDeliveries strictly isolates customer parcels and computes totals")
  void getCustomerDeliveriesIsolatesOwner() {
    String customer = "customer-user-123";
    TrackingParcel p1 = new TrackingParcel();
    p1.setTrackingRef("ZT-AAA1111111");
    p1.setCustomerSubject(customer);
    p1.setFee(new BigDecimal("750.00"));
    p1.setStatus(TrackingStatus.DELIVERED);

    TrackingParcel p2 = new TrackingParcel();
    p2.setTrackingRef("ZT-BBB2222222");
    p2.setCustomerSubject(customer);
    p2.setFee(new BigDecimal("1250.00"));
    p2.setStatus(TrackingStatus.IN_TRANSIT);

    when(parcelRepository.findByCustomerSubject(eq(customer), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(p1, p2)));

    CustomerDeliveryListResponse result = trackingService.getCustomerDeliveries(customer, 1, 10, null, null);

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.content()).hasSize(2);
    assertThat(result.totalSpent()).isEqualByComparingTo("2000.00");
    assertThat(result.formattedTotalSpent()).contains("2,000.00");
  }

  @Test
  @DisplayName("recordEvent updates parcel and notifies stream subscribers")
  void recordEventNotifiesStream() {
    String trackingRef = "ZT-STREAM1234";
    TrackingParcel parcel = new TrackingParcel();
    parcel.setTrackingRef(trackingRef);
    parcel.setStatus(TrackingStatus.CREATED);

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.of(parcel));
    when(eventRepository.save(any(TrackingEvent.class))).thenAnswer(i -> i.getArgument(0));

    TrackingEvent saved = trackingService.recordEvent(
        trackingRef,
        UUID.randomUUID(),
        TrackingStatus.IN_TRANSIT,
        "PICKED_UP",
        "DISPATCHER",
        "In Transit",
        "Moving between hubs",
        null,
        "Colombo Hub",
        Instant.now(),
        UUID.randomUUID()
    );

    assertThat(saved.getStatus()).isEqualTo(TrackingStatus.IN_TRANSIT);
    assertThat(parcel.getStatus()).isEqualTo(TrackingStatus.IN_TRANSIT);
    verify(streamService).notifySubscribers(saved);
  }

  @Test
  @DisplayName("getTrackingEvents throws TrackingNotFoundException when reference is absent from S4 repository")
  void getTrackingEventsThrowsNotFoundWhenAbsent() {
    when(parcelRepository.findByTrackingRef("ZT-NOTFOUND404")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> trackingService.getTrackingEvents("ZT-NOTFOUND404"))
        .isInstanceOf(TrackingNotFoundException.class)
        .hasMessageContaining("ZT-NOTFOUND404");
  }

  @Test
  @DisplayName("getCustomerDeliveries returns empty page without errors when customer has no parcels")
  void getCustomerDeliveriesReturnsEmptyPage() {
    String customer = "customer-empty-user";
    when(parcelRepository.findByCustomerSubject(eq(customer), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse result = trackingService.getCustomerDeliveries(customer, 1, 10, null, null);

    assertThat(result.total()).isEqualTo(0);
    assertThat(result.content()).isEmpty();
    assertThat(result.totalSpent()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.formattedTotalSpent()).isEqualTo("LKR 0.00");
  }

  @Test
  @DisplayName("getCustomerDeliveries filters by status only when status provided")
  void getCustomerDeliveriesFiltersByStatusOnly() {
    String customer = "customer-user-1";
    when(parcelRepository.findByCustomerSubjectAndStatus(eq(customer), eq(TrackingStatus.IN_TRANSIT), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse result = trackingService.getCustomerDeliveries(customer, 1, 10, "IN_TRANSIT", null);

    assertThat(result.total()).isEqualTo(0);
    verify(parcelRepository).findByCustomerSubjectAndStatus(eq(customer), eq(TrackingStatus.IN_TRANSIT), any(Pageable.class));
  }

  @Test
  @DisplayName("getCustomerDeliveries filters by query only when keyword query provided")
  void getCustomerDeliveriesFiltersByQueryOnly() {
    String customer = "customer-user-2";
    when(parcelRepository.searchByCustomer(eq(customer), eq("Kandy"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse result = trackingService.getCustomerDeliveries(customer, 1, 10, null, "Kandy");

    assertThat(result.total()).isEqualTo(0);
    verify(parcelRepository).searchByCustomer(eq(customer), eq("Kandy"), any(Pageable.class));
  }

  @Test
  @DisplayName("getCustomerDeliveries filters by both status and query when both provided")
  void getCustomerDeliveriesFiltersByStatusAndQuery() {
    String customer = "customer-user-3";
    when(parcelRepository.searchByCustomerAndStatus(eq(customer), eq(TrackingStatus.DELIVERED), eq("ZT-123"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    CustomerDeliveryListResponse result = trackingService.getCustomerDeliveries(customer, 1, 10, "DELIVERED", "ZT-123");

    assertThat(result.total()).isEqualTo(0);
    verify(parcelRepository).searchByCustomerAndStatus(eq(customer), eq(TrackingStatus.DELIVERED), eq("ZT-123"), any(Pageable.class));
  }

  @Test
  @DisplayName("backfillTrackingParcel reconstructs projection from event history when parcel missing")
  void backfillTrackingParcelReconstructsFromEvents() {
    String trackingRef = "ZT-7K3QX9M2PA";
    UUID parcelId = UUID.randomUUID();
    Instant t1 = Instant.parse("2026-11-10T10:00:00Z");
    Instant t2 = Instant.parse("2026-11-10T12:00:00Z");
    Instant t3 = Instant.parse("2026-11-10T15:30:00Z");

    TrackingEvent ev1 = new TrackingEvent(trackingRef, TrackingStatus.CREATED, "Created", "Booked", t1);
    ev1.setParcelId(parcelId);
    TrackingEvent ev2 = new TrackingEvent(trackingRef, TrackingStatus.IN_TRANSIT, "In Transit", "On route", t2);
    ev2.setParcelId(parcelId);
    TrackingEvent ev3 = new TrackingEvent(trackingRef, TrackingStatus.DELIVERED, "Delivered", "Delivered successfully", t3);
    ev3.setParcelId(parcelId);

    when(parcelRepository.findByTrackingRef(trackingRef)).thenReturn(Optional.empty());
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of(ev1, ev2, ev3));
    when(parcelRepository.save(any(TrackingParcel.class))).thenAnswer(i -> i.getArgument(0));

    TrackingParcel backfilled = trackingService.backfillTrackingParcel(trackingRef);

    assertThat(backfilled).isNotNull();
    assertThat(backfilled.getTrackingRef()).isEqualTo(trackingRef);
    assertThat(backfilled.getParcelId()).isEqualTo(parcelId);
    assertThat(backfilled.getStatus()).isEqualTo(TrackingStatus.DELIVERED);
    assertThat(backfilled.getDeliveredAt()).isEqualTo(t3);
    verify(parcelRepository).save(any(TrackingParcel.class));
  }

  @Test
  @DisplayName("backfillTrackingParcel throws TrackingNotFoundException when no events exist")
  void backfillTrackingParcelThrowsNotFoundWhenNoEvents() {
    String trackingRef = "ZT-NOEVENTS99";
    when(eventRepository.findByTrackingRefOrderByOccurredAtAsc(trackingRef)).thenReturn(List.of());

    assertThatThrownBy(() -> trackingService.backfillTrackingParcel(trackingRef))
        .isInstanceOf(TrackingNotFoundException.class)
        .hasMessageContaining(trackingRef);
  }
}

