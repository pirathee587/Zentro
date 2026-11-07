package com.zentro.trackingreporting.tracking.service;

import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.tracking.dto.CustomerDeliveryResponse;
import com.zentro.trackingreporting.tracking.dto.ParcelDetailsDto;
import com.zentro.trackingreporting.tracking.dto.PartyDto;
import com.zentro.trackingreporting.tracking.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.dto.TrackingMilestoneDto;
import com.zentro.trackingreporting.exception.TrackingNotFoundException;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrackingService {

  private static final Logger log = LoggerFactory.getLogger(TrackingService.class);
  private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
  private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

  private final TrackingParcelRepository parcelRepository;
  private final TrackingEventRepository eventRepository;
  private final TrackingStreamService streamService;

  public TrackingService(
      TrackingParcelRepository parcelRepository,
      TrackingEventRepository eventRepository,
      TrackingStreamService streamService) {
    this.parcelRepository = parcelRepository;
    this.eventRepository = eventRepository;
    this.streamService = streamService;
  }

  @Transactional(readOnly = true)
  public PublicTrackingResponse getPublicTracking(String trackingRef) {
    if (trackingRef == null || trackingRef.isBlank()) {
      throw new TrackingNotFoundException(trackingRef);
    }
    String cleanRef = trackingRef.trim().toUpperCase();
    TrackingParcel parcel = parcelRepository.findByTrackingRef(cleanRef)
        .orElseThrow(() -> new TrackingNotFoundException(cleanRef));

    List<TrackingEvent> events = eventRepository.findByTrackingRefOrderByOccurredAtAsc(cleanRef);
    List<TrackingEventDto> eventDtos = events.stream().map(this::toEventDto).toList();
    List<TrackingMilestoneDto> timeline = buildTimeline(parcel, events);

    Instant estimatedDeliveryDate = parcel.getCreatedAt() != null
        ? parcel.getCreatedAt().plusSeconds(parcel.getEstimatedDays() * 86400L)
        : null;

    // Public view EXCLUDES: senderName, senderPhone, senderAddress, recipientName, recipientPhone, recipientAddress, fee
    return new PublicTrackingResponse(
        parcel.getTrackingRef(),
        parcel.getStatus().portalStatus(),
        parcel.getStatus().name(),
        parcel.getCategory(),
        parcel.getSize(),
        parcel.getDescription(),
        parcel.getSenderCity(),
        parcel.getRecipientCity(),
        parcel.getEstimatedDays(),
        estimatedDeliveryDate,
        parcel.getCreatedAt(),
        parcel.getDeliveredAt(),
        parcel.getFailureReason(),
        eventDtos,
        timeline
    );
  }

  @Transactional(readOnly = true)
  public List<TrackingEventDto> getTrackingEvents(String trackingRef) {
    if (trackingRef == null || trackingRef.isBlank()) {
      throw new TrackingNotFoundException(trackingRef);
    }
    String cleanRef = trackingRef.trim().toUpperCase();
    parcelRepository.findByTrackingRef(cleanRef)
        .orElseThrow(() -> new TrackingNotFoundException(cleanRef));

    List<TrackingEvent> events = eventRepository.findByTrackingRefOrderByOccurredAtAsc(cleanRef);
    return events.stream().map(this::toEventDto).toList();
  }

  @Transactional(readOnly = true)
  public CustomerDeliveryListResponse getCustomerDeliveries(
      String customerSubject,
      int page,
      int size,
      String statusFilter,
      String query) {

    int pageIndex = Math.max(0, page > 0 ? page - 1 : 0);
    int pageSize = Math.min(Math.max(1, size), 100);
    Pageable pageable = PageRequest.of(pageIndex, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));

    Page<TrackingParcel> paged;
    boolean hasStatus = statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter);
    boolean hasQuery = query != null && !query.trim().isEmpty();

    if (hasStatus && hasQuery) {
      TrackingStatus status = TrackingStatus.fromString(statusFilter);
      paged = parcelRepository.searchByCustomerAndStatus(customerSubject, status, query.trim(), pageable);
    } else if (hasStatus) {
      TrackingStatus status = TrackingStatus.fromString(statusFilter);
      paged = parcelRepository.findByCustomerSubjectAndStatus(customerSubject, status, pageable);
    } else if (hasQuery) {
      paged = parcelRepository.searchByCustomer(customerSubject, query.trim(), pageable);
    } else {
      paged = parcelRepository.findByCustomerSubject(customerSubject, pageable);
    }

    List<CustomerDeliveryResponse> list = paged.getContent().stream().map(parcel -> {
      List<TrackingEvent> events = eventRepository.findByTrackingRefOrderByOccurredAtAsc(parcel.getTrackingRef());
      List<TrackingMilestoneDto> timeline = buildTimeline(parcel, events);
      return toCustomerResponse(parcel, timeline);
    }).toList();

    BigDecimal totalSpent = list.stream()
        .map(CustomerDeliveryResponse::fee)
        .filter(f -> f != null)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    return new CustomerDeliveryListResponse(
        list,
        list,
        paged.getTotalElements(),
        paged.getTotalElements(),
        paged.getTotalPages(),
        pageIndex + 1,
        pageSize,
        totalSpent,
        formatMoney(totalSpent)
    );
  }

  @Transactional
  public TrackingEvent recordEvent(
      String trackingRef,
      UUID parcelId,
      TrackingStatus status,
      String previousStatus,
      String actorRole,
      String title,
      String description,
      String failureReason,
      String location,
      Instant occurredAt,
      UUID eventId) {

    String cleanRef = trackingRef.trim().toUpperCase();
    Instant eventTime = occurredAt != null ? occurredAt : Instant.now();

    TrackingEvent event = new TrackingEvent();
    event.setTrackingRef(cleanRef);
    event.setParcelId(parcelId);
    event.setStatus(status);
    event.setPreviousStatus(previousStatus);
    event.setActorRole(actorRole != null ? actorRole : "SYSTEM");
    event.setTitle(title != null ? title : status.defaultTitle());
    event.setDescription(description != null ? description : status.defaultDescription());
    event.setFailureReason(failureReason);
    event.setLocation(location);
    event.setOccurredAt(eventTime);
    event.setEventId(eventId);

    TrackingEvent savedEvent = eventRepository.save(event);

    // Update parcel status if present
    Optional<TrackingParcel> existing = parcelRepository.findByTrackingRef(cleanRef);
    if (existing.isPresent()) {
      TrackingParcel p = existing.get();
      p.setStatus(status);
      p.setFailureReason(failureReason);
      p.setUpdatedAt(Instant.now());
      if (status == TrackingStatus.DELIVERED && p.getDeliveredAt() == null) {
        p.setDeliveredAt(eventTime);
      }
      parcelRepository.save(p);
    }

    // Notify live streaming SSE subscribers
    streamService.notifySubscribers(savedEvent);

    return savedEvent;
  }

  @Transactional
  public TrackingParcel saveParcel(TrackingParcel parcel) {
    TrackingParcel saved = parcelRepository.save(parcel);
    // Ensure initial CREATED event exists
    List<TrackingEvent> existing = eventRepository.findByTrackingRefOrderByOccurredAtAsc(saved.getTrackingRef());
    if (existing.isEmpty()) {
      recordEvent(
          saved.getTrackingRef(),
          saved.getParcelId(),
          saved.getStatus(),
          null,
          "CUSTOMER",
          saved.getStatus().defaultTitle(),
          saved.getStatus().defaultDescription(),
          null,
          saved.getSenderCity(),
          saved.getCreatedAt(),
          null
      );
    }
    return saved;
  }

  private List<TrackingMilestoneDto> buildTimeline(TrackingParcel parcel, List<TrackingEvent> events) {
    List<TrackingMilestoneDto> timeline = new ArrayList<>();
    if (events.isEmpty()) {
      timeline.add(new TrackingMilestoneDto(
          UUID.randomUUID().toString(),
          "Created",
          "Delivery order registered",
          parcel.getCreatedAt(),
          "completed",
          parcel.getSenderCity(),
          null
      ));
      return timeline;
    }

    for (int i = 0; i < events.size(); i++) {
      TrackingEvent ev = events.get(i);
      boolean isLast = (i == events.size() - 1);
      String stepStatus = ev.getStatus() == TrackingStatus.FAILED
          ? "failed"
          : (isLast && ev.getStatus() != TrackingStatus.DELIVERED ? "current" : "completed");

      timeline.add(new TrackingMilestoneDto(
          ev.getId() != null ? ev.getId().toString() : UUID.randomUUID().toString(),
          ev.getTitle(),
          ev.getDescription(),
          ev.getOccurredAt(),
          stepStatus,
          ev.getLocation(),
          ev.getFailureReason()
      ));
    }
    return timeline;
  }

  private TrackingEventDto toEventDto(TrackingEvent ev) {
    return new TrackingEventDto(
        ev.getId(),
        ev.getTrackingRef(),
        ev.getStatus().name(),
        ev.getTitle(),
        ev.getDescription(),
        ev.getActorRole(),
        ev.getLocation(),
        ev.getOccurredAt(),
        ev.getFailureReason()
    );
  }

  private CustomerDeliveryResponse toCustomerResponse(TrackingParcel p, List<TrackingMilestoneDto> timeline) {
    String formattedCreated = p.getCreatedAt() != null ? DISPLAY_DATE.format(p.getCreatedAt()) : null;
    String formattedDelivered = p.getDeliveredAt() != null ? DISPLAY_DATE.format(p.getDeliveredAt()) : null;

    return new CustomerDeliveryResponse(
        p.getId() != null ? p.getId().toString() : (p.getParcelId() != null ? p.getParcelId().toString() : UUID.randomUUID().toString()),
        p.getTrackingRef(),
        p.getStatus().portalStatus(),
        p.getStatus().name(),
        new PartyDto(p.getSenderName(), p.getSenderPhone(), p.getSenderAddress(), p.getSenderCity()),
        new PartyDto(p.getRecipientName(), p.getRecipientPhone(), p.getRecipientAddress(), p.getRecipientCity()),
        new ParcelDetailsDto(p.getDescription(), p.getWeightKg(), p.getCategory(), p.getSize()),
        p.getFee(),
        p.getCurrency(),
        formatMoney(p.getFee()),
        p.getEstimatedDays(),
        p.getCreatedAt(),
        formattedCreated,
        p.getDeliveredAt(),
        formattedDelivered,
        p.getFailureReason(),
        timeline
    );
  }

  private String formatMoney(BigDecimal amount) {
    if (amount == null) return "LKR 0";
    NumberFormat nf = NumberFormat.getCurrencyInstance(Locale.ENGLISH);
    String symbol = nf.format(amount);
    return "LKR " + String.format(Locale.ENGLISH, "%,.2f", amount);
  }
}

