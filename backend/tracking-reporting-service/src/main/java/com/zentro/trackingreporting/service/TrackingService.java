package com.zentro.trackingreporting.service;

import com.zentro.trackingreporting.domain.TrackingEvent;
import com.zentro.trackingreporting.domain.TrackingParcel;
import com.zentro.trackingreporting.domain.TrackingStatus;
import com.zentro.trackingreporting.dto.CustomerDeliveryListResponse;
import com.zentro.trackingreporting.dto.CustomerDeliveryResponse;
import com.zentro.trackingreporting.dto.ParcelDetailsDto;
import com.zentro.trackingreporting.dto.PartyDto;
import com.zentro.trackingreporting.dto.PublicTrackingResponse;
import com.zentro.trackingreporting.dto.TrackingEventDto;
import com.zentro.trackingreporting.dto.TrackingMilestoneDto;
import com.zentro.trackingreporting.error.TrackingNotFoundException;
import com.zentro.trackingreporting.repository.TrackingEventRepository;
import com.zentro.trackingreporting.repository.TrackingParcelRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
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
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
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
  private final JdbcTemplate jdbcTemplate;

  public TrackingService(
      TrackingParcelRepository parcelRepository,
      TrackingEventRepository eventRepository,
      TrackingStreamService streamService,
      JdbcTemplate jdbcTemplate) {
    this.parcelRepository = parcelRepository;
    this.eventRepository = eventRepository;
    this.streamService = streamService;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Transactional(readOnly = true)
  public PublicTrackingResponse getPublicTracking(String trackingRef) {
    if (trackingRef == null || trackingRef.isBlank()) {
      throw new TrackingNotFoundException(trackingRef);
    }
    String cleanRef = trackingRef.trim().toUpperCase();
    TrackingParcel parcel = findOrBackfillParcel(cleanRef)
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
    findOrBackfillParcel(cleanRef).orElseThrow(() -> new TrackingNotFoundException(cleanRef));

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

    // Attempt backfill for this customer if parcel table exists in shared DB
    backfillCustomerParcels(customerSubject);

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

  private Optional<TrackingParcel> findOrBackfillParcel(String trackingRef) {
    Optional<TrackingParcel> existing = parcelRepository.findByTrackingRef(trackingRef);
    if (existing.isPresent()) {
      return existing;
    }
    return backfillFromSharedParcelTable(trackingRef);
  }

  private Optional<TrackingParcel> backfillFromSharedParcelTable(String trackingRef) {
    try {
      String sql = "SELECT * FROM parcel.parcel WHERE tracking_ref = ?";
      List<TrackingParcel> results = jdbcTemplate.query(sql, (rs, rowNum) -> mapFromParcelRow(rs), trackingRef);
      if (!results.isEmpty()) {
        TrackingParcel backfilled = parcelRepository.save(results.get(0));
        backfillHistoryForParcel(backfilled);
        return Optional.of(backfilled);
      }
    } catch (DataAccessException e) {
      log.debug("parcel.parcel table not reachable or does not exist: {}", e.getMessage());
    }
    return Optional.empty();
  }

  private void backfillCustomerParcels(String customerSubject) {
    try {
      String sql = "SELECT * FROM parcel.parcel WHERE customer_subject = ?";
      List<TrackingParcel> results = jdbcTemplate.query(sql, (rs, rowNum) -> mapFromParcelRow(rs), customerSubject);
      for (TrackingParcel p : results) {
        if (parcelRepository.findByTrackingRef(p.getTrackingRef()).isEmpty()) {
          TrackingParcel saved = parcelRepository.save(p);
          backfillHistoryForParcel(saved);
        }
      }
    } catch (DataAccessException e) {
      log.debug("parcel.parcel table not reachable for customer backfill: {}", e.getMessage());
    }
  }

  private void backfillHistoryForParcel(TrackingParcel parcel) {
    // Record initial CREATED event
    if (eventRepository.findByTrackingRefOrderByOccurredAtAsc(parcel.getTrackingRef()).isEmpty()) {
      recordEvent(
          parcel.getTrackingRef(),
          parcel.getParcelId(),
          TrackingStatus.CREATED,
          null,
          "CUSTOMER",
          "Parcel Booked",
          "Delivery order created and registered in system",
          null,
          parcel.getSenderCity(),
          parcel.getCreatedAt(),
          null
      );
    }

    // Also check parcel.parcel_status_history if available
    try {
      String sql = "SELECT * FROM parcel.parcel_status_history WHERE parcel_id = ? ORDER BY occurred_at ASC";
      jdbcTemplate.query(sql, (ResultSet rs) -> {
        TrackingStatus status = TrackingStatus.fromString(rs.getString("to_status"));
        String fromStatus = rs.getString("from_status");
        String role = rs.getString("actor_role");
        String reason = rs.getString("reason");
        Instant occurredAt = rs.getTimestamp("occurred_at").toInstant();
        recordEvent(
            parcel.getTrackingRef(),
            parcel.getParcelId(),
            status,
            fromStatus,
            role,
            status.defaultTitle(),
            reason != null ? reason : status.defaultDescription(),
            reason,
            null,
            occurredAt,
            null
        );
      }, parcel.getParcelId());
    } catch (DataAccessException e) {
      log.debug("parcel.parcel_status_history table not reachable: {}", e.getMessage());
    }
  }

  private TrackingParcel mapFromParcelRow(ResultSet rs) throws java.sql.SQLException {
    TrackingParcel p = new TrackingParcel();
    p.setParcelId(rs.getObject("id", UUID.class));
    p.setTrackingRef(rs.getString("tracking_ref"));
    p.setCustomerSubject(rs.getString("customer_subject"));
    p.setSenderName(rs.getString("sender_name"));
    p.setSenderPhone(rs.getString("sender_phone"));
    p.setSenderAddress(rs.getString("sender_address"));
    p.setSenderCity(rs.getString("sender_city"));
    p.setRecipientName(rs.getString("recipient_name"));
    p.setRecipientPhone(rs.getString("recipient_phone"));
    p.setRecipientAddress(rs.getString("recipient_address"));
    p.setRecipientCity(rs.getString("recipient_city"));
    p.setWeightKg(rs.getBigDecimal("weight_kg"));
    p.setSize(rs.getString("size"));
    p.setCategory(rs.getString("category"));
    p.setDescription(rs.getString("description"));
    p.setDestinationType(rs.getString("destination_type"));
    p.setExpress(rs.getBoolean("is_express"));
    p.setFee(rs.getBigDecimal("fee"));
    p.setCurrency(rs.getString("currency"));
    p.setEstimatedDays(rs.getInt("estimated_days"));
    p.setStatus(TrackingStatus.fromString(rs.getString("status")));
    p.setCreatedAt(rs.getTimestamp("created_at").toInstant());
    if (rs.getTimestamp("updated_at") != null) {
      p.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
    }
    return p;
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

