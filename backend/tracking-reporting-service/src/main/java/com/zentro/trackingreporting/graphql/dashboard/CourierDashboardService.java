package com.zentro.trackingreporting.graphql.dashboard;

import com.zentro.trackingreporting.graphql.dashboard.CourierAssignmentSnapshotClient.Assignment;
import com.zentro.trackingreporting.graphql.dashboard.CourierTodayDashboardDto.Counts;
import com.zentro.trackingreporting.graphql.dashboard.CourierTodayDashboardDto.Delivery;
import com.zentro.trackingreporting.graphql.dto.PageInfoDto;
import com.zentro.trackingreporting.tracking.dto.PartyDto;
import com.zentro.trackingreporting.tracking.entity.TrackingEvent;
import com.zentro.trackingreporting.tracking.entity.TrackingParcel;
import com.zentro.trackingreporting.tracking.entity.TrackingStatus;
import com.zentro.trackingreporting.tracking.repository.TrackingEventRepository;
import com.zentro.trackingreporting.tracking.repository.TrackingParcelRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

@Service
public class CourierDashboardService {
  private static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
  private final CourierAssignmentSnapshotClient snapshots;
  private final TrackingParcelRepository parcels;
  private final TrackingEventRepository events;
  private final Clock clock;
  private CourierEventProjectionRepository eventProjections;
  @Autowired public CourierDashboardService(CourierAssignmentSnapshotClient snapshots,
      TrackingParcelRepository parcels, TrackingEventRepository events, CourierEventProjectionRepository eventProjections) {
    this(snapshots, parcels, events, Clock.systemUTC());
    this.eventProjections=eventProjections;
  }
  public CourierDashboardService(CourierAssignmentSnapshotClient snapshots,
      TrackingParcelRepository parcels, TrackingEventRepository events, Clock clock) {
    this.snapshots = snapshots; this.parcels = parcels; this.events = events; this.clock = clock;
  }

  // Deliberately no surrounding transaction: network calls must not hold DB connections/locks.
  public CourierTodayDashboardDto dashboard(Integer requestedPage, Integer requestedSize, Authentication auth) {
    if (!(auth instanceof JwtAuthenticationToken jwt) || !auth.isAuthenticated()
        || jwt.getToken().getSubject() == null || jwt.getToken().getSubject().isBlank())
      throw new AuthenticationCredentialsNotFoundException("Sign in to view courier deliveries.");
    var roles = auth.getAuthorities().stream().map(a -> a.getAuthority()).collect(Collectors.toSet());
    if (!roles.contains("ROLE_COURIER") || roles.contains("ROLE_SERVICE"))
      throw new AccessDeniedException("Courier authority is required.");
    int page = requestedPage == null ? 1 : requestedPage;
    int size = requestedSize == null ? 10 : requestedSize;
    if (page < 1 || size < 1) throw new IllegalArgumentException("Page and size must be positive.");
    size = Math.min(size, 50);
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZONE);
    Instant start = today.atStartOfDay(ZONE).toInstant();
    Instant end = today.plusDays(1).atStartOfDay(ZONE).toInstant();
    String token = jwt.getToken().getTokenValue();
    List<Assignment> assigned = snapshots.snapshot(token);
    var ids = assigned.stream().map(Assignment::parcelId).toList();
    Map<UUID, TrackingParcel> projections;
    List<TrackingEvent> terminalEvents;
    try {
      projections = ids.isEmpty() ? Map.of() : parcels.findByParcelIdIn(ids).stream()
          .collect(Collectors.toMap(TrackingParcel::getParcelId, Function.identity()));
      var missing=ids.stream().filter(id->!projections.containsKey(id)).toList();
      if(eventProjections!=null&&!missing.isEmpty())
        eventProjections.find(missing).forEach(p->projections.put(p.getParcelId(),p));
      var terminalIds = projections.values().stream().filter(p -> terminal(p.getStatus()))
          .map(TrackingParcel::getParcelId).toList();
      terminalEvents = terminalIds.isEmpty() ? List.of()
          : events.findByParcelIdInAndOccurredAtBetweenOrderByOccurredAtDesc(terminalIds, start, now);
    } catch (DataAccessException | TransactionException failure) {
      throw new CourierDashboardException("DASHBOARD_PROJECTION_UNAVAILABLE");
    }
    var terminalTimes = terminalEvents.stream().filter(e -> e.getEventId() != null && e.getParcelId() != null
        && e.getStatus() != null && e.getOccurredAt() != null)
        .collect(Collectors.toMap(e -> new TerminalKey(e.getParcelId(), e.getStatus()),
            TrackingEvent::getOccurredAt, (a, b) -> a.isAfter(b) ? a : b));
    var population = assigned.stream().filter(a -> {
      var p = projections.get(a.parcelId());
      if (p == null || !terminal(p.getStatus())) return true;
      Instant completed = terminalTimes.get(new TerminalKey(a.parcelId(), p.getStatus()));
      if (completed == null && p.getStatus() == TrackingStatus.DELIVERED) completed = p.getDeliveredAt();
      return completed != null && !completed.isBefore(a.assignedAt()) && !completed.isBefore(start)
          && completed.isBefore(end) && !completed.isAfter(now);
    }).sorted(Comparator.comparing(Assignment::assignedAt).thenComparing(Assignment::parcelId)).toList();
    var all = population.stream().map(a -> delivery(a, projections.get(a.parcelId()))).toList();
    int delivered = (int) all.stream().filter(d -> d.status() == TrackingStatus.DELIVERED).count();
    int failed = (int) all.stream().filter(d -> d.status() == TrackingStatus.FAILED).count();
    Delivery current = all.stream().filter(d -> d.status() != null && !terminal(d.status()))
        .min(Comparator.comparingInt((Delivery d) -> d.status() == TrackingStatus.OUT_FOR_DELIVERY ? 0 : 1)
            .thenComparing(d -> Instant.parse(d.assignedAt())).thenComparing(Delivery::parcelId)).orElse(null);
    // A second authoritative snapshot detects cancellation/reassignment while projections were read.
    if (!assigned.isEmpty() && !ownership(assigned).equals(ownership(snapshots.snapshot(token))))
      throw new CourierDashboardException("ASSIGNMENTS_CHANGED");
    int offset = (int) Math.min((long) (page - 1) * size, all.size());
    int to = Math.min(offset + size, all.size());
    int totalPages = (all.size() + size - 1) / size;
    return new CourierTodayDashboardDto(today.toString(), ZONE.getId(),
        new Counts(all.size(), delivered, failed, all.size() - delivered - failed), current,
        all.subList(offset, to), new PageInfoDto(page, size, all.size(), totalPages, page < totalPages, page > 1));
  }

  private static Map<UUID, Instant> ownership(List<Assignment> rows) {
    return rows.stream().collect(Collectors.toMap(Assignment::parcelId, Assignment::assignedAt));
  }
  private record TerminalKey(UUID parcelId, TrackingStatus status) {}
  private static boolean terminal(TrackingStatus status) {
    return status == TrackingStatus.DELIVERED || status == TrackingStatus.FAILED;
  }
  private static Delivery delivery(Assignment a, TrackingParcel p) {
    return new Delivery(a.parcelId(), a.assignedAt().toString(), a.assignmentState(), p != null,
        p == null ? null : p.getTrackingRef(), p == null ? null : p.getStatus(),
        p == null || p.getRecipientName()==null || p.getRecipientPhone()==null || p.getRecipientAddress()==null ? null : new PartyDto(p.getRecipientName(), p.getRecipientPhone(), p.getRecipientAddress(), p.getRecipientCity()),
        p == null || p.getWeightKg() == null ? null : p.getWeightKg().doubleValue(),
        p == null ? null : p.getSize(), p == null ? null : p.getFailureReason(), a.proofId(), a.completionState());
  }
}
