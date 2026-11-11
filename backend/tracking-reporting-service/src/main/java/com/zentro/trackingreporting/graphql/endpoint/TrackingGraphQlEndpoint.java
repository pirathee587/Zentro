package com.zentro.trackingreporting.graphql.endpoint;

import com.zentro.trackingreporting.exception.TrackingNotFoundException;
import com.zentro.trackingreporting.graphql.dto.DeliveryConnectionDto;
import com.zentro.trackingreporting.graphql.dto.DeliveryDetailsDto;
import com.zentro.trackingreporting.graphql.dto.PageInfoDto;
import com.zentro.trackingreporting.graphql.dto.PublicTrackingParcelDto;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.dto.PartyDto;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Controller;

@Controller
public class TrackingGraphQlEndpoint {

  private static final Pattern TRACKING_REF_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{6,32}$");
  private static final int DEFAULT_PAGE_SIZE = 10;
  private static final int MAX_PAGE_SIZE = 50;

  private final TrackingParcelRepository parcelRepository;
  private final TrackingEventRepository eventRepository;
  private final DeliveryAuthorizationService authService;

  public TrackingGraphQlEndpoint(
      TrackingParcelRepository parcelRepository,
      TrackingEventRepository eventRepository,
      DeliveryAuthorizationService authService) {
    this.parcelRepository = parcelRepository;
    this.eventRepository = eventRepository;
    this.authService = authService;
  }

  /**
   * Public tracking query. Excludes sensitive customer and merchant details (PII, phone, address, fee).
   */
  @QueryMapping
  public PublicTrackingParcelDto publicTracking(@Argument("trackingRef") String trackingRef) {
    validateTrackingRef(trackingRef);
    TrackingParcel parcel = parcelRepository.findByTrackingRef(trackingRef)
        .orElseThrow(() -> new TrackingNotFoundException("Parcel not found for tracking reference: " + trackingRef));
    return toPublicTrackingParcelDto(parcel);
  }

  /**
   * Authenticated delivery query. Enforces resource ownership.
   */
  @QueryMapping
  public DeliveryDetailsDto delivery(
      @Argument("trackingRef") String trackingRef,
      Authentication authentication) {
    requireAuthenticatedUser(authentication);
    validateTrackingRef(trackingRef);
    TrackingParcel parcel = parcelRepository.findByTrackingRef(trackingRef)
        .orElseThrow(() -> new TrackingNotFoundException("Parcel not found for tracking reference: " + trackingRef));

    if (!authService.isAuthorized(authentication, parcel)) {
      throw new AccessDeniedException("Access denied for tracking reference: " + trackingRef);
    }
    return toDeliveryDetailsDto(parcel);
  }

  /**
   * Paginated deliveries for the caller's identity (customer, merchant, courier, or dispatcher).
   */
  @QueryMapping
  public DeliveryConnectionDto myDeliveries(
      @Argument("page") Integer page,
      @Argument("size") Integer size,
      @Argument("status") TrackingStatus status,
      @Argument("query") String query,
      Authentication authentication) {
    requireAuthenticatedUser(authentication);

    Pageable pageable = buildPageable(page, size);
    String subject = authService.extractSubject(authentication);
    Set<String> roles = extractRoles(authentication);
    String sanitizedQuery = (query != null && !query.isBlank()) ? query.trim() : null;

    Page<TrackingParcel> pageResult;
    if (roles.contains(DeliveryAuthorizationService.ROLE_DISPATCHER)) {
      pageResult = findForDispatcher(status, sanitizedQuery, pageable);
    } else if (roles.contains(DeliveryAuthorizationService.ROLE_CUSTOMER)) {
      pageResult = findForCustomer(subject, status, sanitizedQuery, pageable);
    } else if (roles.contains(DeliveryAuthorizationService.ROLE_MERCHANT)) {
      pageResult = findForMerchant(subject, status, sanitizedQuery, pageable);
    } else if (roles.contains(DeliveryAuthorizationService.ROLE_COURIER)) {
      pageResult = findForCourier(subject, status, sanitizedQuery, pageable);
    } else {
      throw new AccessDeniedException("Access denied: caller does not have an authorized delivery role");
    }

    return toDeliveryConnectionDto(pageResult, pageable.getPageNumber() + 1, pageable.getPageSize());
  }

  /**
   * Platform-wide delivery query. Strictly restricted to ROLE_DISPATCHER.
   */
  @QueryMapping
  public DeliveryConnectionDto deliveries(
      @Argument("page") Integer page,
      @Argument("size") Integer size,
      @Argument("status") TrackingStatus status,
      @Argument("query") String query,
      Authentication authentication) {
    requireAuthenticatedUser(authentication);
    Set<String> roles = extractRoles(authentication);
    if (!roles.contains(DeliveryAuthorizationService.ROLE_DISPATCHER)) {
      throw new AccessDeniedException("Access denied: deliveries query requires ROLE_DISPATCHER");
    }

    Pageable pageable = buildPageable(page, size);
    String sanitizedQuery = (query != null && !query.isBlank()) ? query.trim() : null;
    Page<TrackingParcel> pageResult = findForDispatcher(status, sanitizedQuery, pageable);
    return toDeliveryConnectionDto(pageResult, pageable.getPageNumber() + 1, pageable.getPageSize());
  }

  /**
   * Batch resolver for DeliveryDetails events, preventing N+1 queries.
   */
  @BatchMapping(typeName = "DeliveryDetails", field = "events")
  public Map<DeliveryDetailsDto, List<TrackingEventDto>> events(List<DeliveryDetailsDto> deliveries) {
    if (deliveries == null || deliveries.isEmpty()) {
      return Collections.emptyMap();
    }
    List<String> refs = deliveries.stream()
        .map(DeliveryDetailsDto::trackingRef)
        .filter(Objects::nonNull)
        .distinct()
        .toList();

    List<TrackingEvent> events = eventRepository.findByTrackingRefInOrderByOccurredAtAsc(refs);
    Map<String, List<TrackingEventDto>> grouped = events.stream()
        .map(this::toEventDto)
        .collect(Collectors.groupingBy(TrackingEventDto::trackingRef));

    Map<DeliveryDetailsDto, List<TrackingEventDto>> result = new LinkedHashMap<>();
    for (DeliveryDetailsDto delivery : deliveries) {
      result.put(delivery, grouped.getOrDefault(delivery.trackingRef(), List.of()));
    }
    return result;
  }

  /**
   * Batch resolver for PublicTrackingParcel events, preventing N+1 queries.
   */
  @BatchMapping(typeName = "PublicTrackingParcel", field = "events")
  public Map<PublicTrackingParcelDto, List<TrackingEventDto>> publicEvents(List<PublicTrackingParcelDto> parcels) {
    if (parcels == null || parcels.isEmpty()) {
      return Collections.emptyMap();
    }
    List<String> refs = parcels.stream()
        .map(PublicTrackingParcelDto::trackingRef)
        .filter(Objects::nonNull)
        .distinct()
        .toList();

    List<TrackingEvent> events = eventRepository.findByTrackingRefInOrderByOccurredAtAsc(refs);
    Map<String, List<TrackingEventDto>> grouped = events.stream()
        .map(this::toEventDto)
        .collect(Collectors.groupingBy(TrackingEventDto::trackingRef));

    Map<PublicTrackingParcelDto, List<TrackingEventDto>> result = new LinkedHashMap<>();
    for (PublicTrackingParcelDto parcel : parcels) {
      result.put(parcel, grouped.getOrDefault(parcel.trackingRef(), List.of()));
    }
    return result;
  }

  // --- Helper Query Methods ---

  private Page<TrackingParcel> findForDispatcher(TrackingStatus status, String query, Pageable pageable) {
    if (query != null && status != null) {
      return parcelRepository.searchAllAndStatus(status, query, pageable);
    } else if (query != null) {
      return parcelRepository.searchAll(query, pageable);
    } else if (status != null) {
      return parcelRepository.findByStatus(status, pageable);
    } else {
      return parcelRepository.findAll(pageable);
    }
  }

  private Page<TrackingParcel> findForCustomer(String subject, TrackingStatus status, String query, Pageable pageable) {
    if (query != null && status != null) {
      return parcelRepository.searchByCustomerAndStatus(subject, status, query, pageable);
    } else if (query != null) {
      return parcelRepository.searchByCustomer(subject, query, pageable);
    } else if (status != null) {
      return parcelRepository.findByCustomerSubjectAndStatus(subject, status, pageable);
    } else {
      return parcelRepository.findByCustomerSubject(subject, pageable);
    }
  }

  private Page<TrackingParcel> findForMerchant(String subject, TrackingStatus status, String query, Pageable pageable) {
    if (query != null && status != null) {
      return parcelRepository.searchByMerchantAndStatus(subject, status, query, pageable);
    } else if (query != null) {
      return parcelRepository.searchByMerchant(subject, query, pageable);
    } else if (status != null) {
      return parcelRepository.findByMerchantSubjectAndStatus(subject, status, pageable);
    } else {
      return parcelRepository.findByMerchantSubject(subject, pageable);
    }
  }

  private Page<TrackingParcel> findForCourier(String subject, TrackingStatus status, String query, Pageable pageable) {
    if (query != null && status != null) {
      return parcelRepository.searchByCourierAndStatus(subject, status, query, pageable);
    } else if (query != null) {
      return parcelRepository.searchByCourier(subject, query, pageable);
    } else if (status != null) {
      return parcelRepository.findByCourierSubjectAndStatus(subject, status, pageable);
    } else {
      return parcelRepository.findByCourierSubject(subject, pageable);
    }
  }

  private Pageable buildPageable(Integer page, Integer size) {
    int pageNum = (page != null) ? page : 1;
    if (pageNum < 1) {
      throw new IllegalArgumentException("Page number must be greater than or equal to 1");
    }
    int pageSize = (size != null) ? size : DEFAULT_PAGE_SIZE;
    if (pageSize < 1) {
      throw new IllegalArgumentException("Page size must be greater than or equal to 1");
    }
    if (pageSize > MAX_PAGE_SIZE) {
      pageSize = MAX_PAGE_SIZE;
    }
    return PageRequest.of(pageNum - 1, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
  }

  private void validateTrackingRef(String trackingRef) {
    if (trackingRef == null || !TRACKING_REF_PATTERN.matcher(trackingRef).matches()) {
      throw new IllegalArgumentException("Invalid tracking reference format");
    }
  }

  private void requireAuthenticatedUser(Authentication authentication) {
    if (authentication == null || !authentication.isAuthenticated()
        || "anonymousUser".equalsIgnoreCase(authentication.getName())) {
      throw new AccessDeniedException("Authentication is required to access this resource");
    }
  }

  private Set<String> extractRoles(Authentication authentication) {
    if (authentication == null) {
      return Collections.emptySet();
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .collect(Collectors.toSet());
  }

  // --- DTO Mappings ---

  private PublicTrackingParcelDto toPublicTrackingParcelDto(TrackingParcel parcel) {
    String estimatedDeliveryDate = null;
    if (parcel.getCreatedAt() != null && parcel.getEstimatedDays() > 0) {
      estimatedDeliveryDate = parcel.getCreatedAt().plus(parcel.getEstimatedDays(), ChronoUnit.DAYS).toString();
    }

    return new PublicTrackingParcelDto(
        parcel.getTrackingRef(),
        parcel.getStatus(),
        parcel.getStatus() != null ? parcel.getStatus().name() : "",
        parcel.getCategory(),
        parcel.getSize(),
        parcel.getDescription(),
        parcel.getSenderCity(),
        parcel.getRecipientCity(),
        parcel.getEstimatedDays(),
        estimatedDeliveryDate,
        parcel.getCreatedAt() != null ? parcel.getCreatedAt().toString() : null,
        parcel.getDeliveredAt() != null ? parcel.getDeliveredAt().toString() : null,
        parcel.getFailureReason(),
        Collections.emptyList()
    );
  }

  private DeliveryDetailsDto toDeliveryDetailsDto(TrackingParcel parcel) {
    PartyDto sender = new PartyDto(
        parcel.getSenderName(),
        parcel.getSenderPhone(),
        parcel.getSenderAddress(),
        parcel.getSenderCity()
    );
    PartyDto recipient = new PartyDto(
        parcel.getRecipientName(),
        parcel.getRecipientPhone(),
        parcel.getRecipientAddress(),
        parcel.getRecipientCity()
    );

    String formattedFee = null;
    if (parcel.getFee() != null && parcel.getCurrency() != null) {
      formattedFee = parcel.getCurrency() + " " + parcel.getFee().toPlainString();
    }

    return new DeliveryDetailsDto(
        parcel.getId(),
        parcel.getTrackingRef(),
        parcel.getStatus(),
        parcel.getStatus() != null ? parcel.getStatus().name() : "",
        parcel.getCategory(),
        parcel.getSize(),
        parcel.getDescription(),
        parcel.getDestinationType(),
        parcel.isExpress(),
        sender,
        recipient,
        parcel.getFee(),
        parcel.getCurrency(),
        formattedFee,
        parcel.getEstimatedDays(),
        parcel.getCreatedAt() != null ? parcel.getCreatedAt().toString() : null,
        parcel.getDeliveredAt() != null ? parcel.getDeliveredAt().toString() : null,
        parcel.getFailureReason(),
        Collections.emptyList()
    );
  }

  private DeliveryConnectionDto toDeliveryConnectionDto(Page<TrackingParcel> page, int requestedPage, int requestedSize) {
    List<DeliveryDetailsDto> items = page.getContent().stream()
        .map(this::toDeliveryDetailsDto)
        .toList();
    PageInfoDto pageInfo = new PageInfoDto(
        requestedPage,
        requestedSize,
        (int) page.getTotalElements(),
        page.getTotalPages(),
        page.hasNext(),
        page.hasPrevious()
    );
    return new DeliveryConnectionDto(items, pageInfo);
  }

  private TrackingEventDto toEventDto(TrackingEvent event) {
    return new TrackingEventDto(
        event.getEventId(),
        event.getTrackingRef(),
        event.getStatus() != null ? event.getStatus().name() : null,
        event.getTitle(),
        event.getDescription(),
        event.getActorRole(),
        event.getLocation(),
        event.getOccurredAt(),
        event.getFailureReason()
    );
  }
}

