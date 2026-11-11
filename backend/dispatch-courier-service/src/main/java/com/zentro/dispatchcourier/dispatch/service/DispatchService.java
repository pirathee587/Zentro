package com.zentro.dispatchcourier.dispatch.service;

import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.exception.DispatchException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DispatchService {
  public record Queue(List<ParcelSource.Parcel> items, Long total, Long matchingTotal, boolean complete) {}
  public record Workload(List<CourierWorkload> items, long total, boolean overloadConfigured) {}
  public record CourierWorkload(UUID courierId, UUID userId, String name, boolean active,
                               String accountStatus, long assignmentCount, Availability availability) {}
  public enum Availability { AVAILABLE, UNAVAILABLE }
  private final ParcelSource parcels;
  private final AssignmentRepository assignments;
  private final IdentityCourierClient identity;
  private final int maxCourierWorkload;

  public DispatchService(ParcelSource parcels, AssignmentRepository assignments, IdentityCourierClient identity) {
    this(parcels, assignments, identity, 10);
  }

  @Autowired
  public DispatchService(ParcelSource parcels, AssignmentRepository assignments, IdentityCourierClient identity,
      @Value("${zentro.dispatch.max-courier-workload:10}") int maxCourierWorkload) {
    this.parcels = parcels;
    this.assignments = assignments;
    this.identity = identity;
    this.maxCourierWorkload = maxCourierWorkload;
  }

  public Queue queue(String token, String search, LocalDate from, LocalDate to, String sort) {
    if (search.length() > 200 || (from != null && to != null && from.isAfter(to))
        || !("newest".equals(sort) || "oldest".equals(sort))) {
      throw new DispatchException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "Check search length, date range and sort order.");
    }
    ParcelSource.Snapshot snapshot = parcels.eligibleParcels(token);
    if (snapshot == null || snapshot.parcels() == null) throw invalidSource();
    var seen = new java.util.HashSet<UUID>();
    for (var parcel : snapshot.parcels()) {
      if (parcel == null || parcel.parcelId() == null || parcel.createdAt() == null || !seen.add(parcel.parcelId())) {
        throw invalidSource();
      }
    }
    var assigned = assignments.assignedIds(snapshot.parcels().stream().map(ParcelSource.Parcel::parcelId).toList());
    List<ParcelSource.Parcel> unassigned = snapshot.parcels().stream()
        .filter(parcel -> !assigned.contains(parcel.parcelId())).distinct().toList();
    String term = search.trim().toLowerCase(Locale.ROOT);
    Comparator<ParcelSource.Parcel> order = Comparator.comparing(ParcelSource.Parcel::createdAt)
        .thenComparing(ParcelSource.Parcel::parcelId);
    if ("newest".equals(sort)) order = order.reversed();
    var items = unassigned.stream().filter(parcel -> term.isEmpty() || text(parcel).contains(term))
        .filter(parcel -> from == null || !parcel.createdAt().isBefore(from.atStartOfDay().toInstant(ZoneOffset.UTC)))
        .filter(parcel -> to == null || !parcel.createdAt().atOffset(ZoneOffset.UTC).toLocalDate().isAfter(to))
        .sorted(order).toList();
    return new Queue(items, snapshot.complete() ? (long) unassigned.size() : null,
        snapshot.complete() ? (long) items.size() : null, snapshot.complete());
  }

  private static String text(ParcelSource.Parcel parcel) {
    return (safe(parcel.trackingReference()) + " " + safe(parcel.pickupSummary()) + " "
        + safe(parcel.dropoffSummary())).toLowerCase(Locale.ROOT);
  }
  private static String safe(String text) { return text == null ? "" : text; }
  private static DispatchException invalidSource() {
    return new DispatchException(HttpStatus.SERVICE_UNAVAILABLE, "INVALID_PARCEL_SOURCE", "Parcel eligibility could not be verified.");
  }

  public List<IdentityCourierClient.Courier> couriers(UUID parcelId, String token) {
    if (assignments.exists(parcelId)) throw DispatchException.assigned();
    return parcels.withEligibleParcel(parcelId, token, parcel -> {
      if (parcel == null || !parcelId.equals(parcel.parcelId())) throw invalidSource();
      var counts = assignments.assignmentCounts();
      return identity.listEligible(token).stream()
          .filter(DispatchService::eligible)
          .filter(courier -> isWorkloadEligible(courier, counts))
          .filter(courier -> isServiceAreaEligible(courier, parcel))
          .toList();
    });
  }

  public static boolean isServiceAreaEligible(IdentityCourierClient.Courier courier, ParcelSource.Parcel parcel) {
    if (courier == null || courier.assignedZone() == null || courier.assignedZone().isBlank()) {
      return true;
    }
    if (parcel == null) {
      return false;
    }
    String courierZone = courier.assignedZone().trim();
    String parcelArea = parcel.effectiveServiceArea();
    if (parcelArea == null || parcelArea.isBlank()) {
      return true;
    }
    String normCourierZone = courierZone.toLowerCase(Locale.ROOT);
    String normParcelArea = parcelArea.toLowerCase(Locale.ROOT);
    return normCourierZone.equals(normParcelArea)
        || normParcelArea.contains(normCourierZone)
        || normCourierZone.contains(normParcelArea);
  }

  private boolean isWorkloadEligible(IdentityCourierClient.Courier courier, Map<UUID, Long> counts) {
    if (maxCourierWorkload <= 0 || courier == null || courier.id() == null) {
      return true;
    }
    return counts.getOrDefault(courier.id(), 0L) < maxCourierWorkload;
  }

  private boolean isWorkloadExceeded(UUID courierId) {
    if (maxCourierWorkload <= 0 || courierId == null) {
      return false;
    }
    return assignments.getAssignmentCount(courierId) >= maxCourierWorkload;
  }

  public Workload workload(String token, String search, String availability) {
    if (search.length() > 200 || !(availability.isBlank() || "available".equals(availability)
        || "unavailable".equals(availability))) {
      throw new DispatchException(HttpStatus.BAD_REQUEST, "INVALID_WORKLOAD_FILTER",
          "Check search length and availability.");
    }
    var counts = assignments.assignmentCounts();
    String term = search.trim().toLowerCase(Locale.ROOT);
    var items = identity.listAll(token).stream()
        .map(courier -> new CourierWorkload(courier.id(), courier.userId(), courier.name(),
            Boolean.TRUE.equals(courier.active()), courier.accountStatus(),
            counts.getOrDefault(courier.id(), 0L),
            eligible(courier) ? Availability.AVAILABLE : Availability.UNAVAILABLE))
        .filter(courier -> term.isEmpty() || safe(courier.name()).toLowerCase(Locale.ROOT).contains(term))
        .filter(courier -> availability.isBlank() || availability.equalsIgnoreCase(courier.availability().name()))
        .sorted(Comparator.comparing(CourierWorkload::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
            .thenComparing(CourierWorkload::courierId))
        .toList();
    return new Workload(items, items.size(), false);
  }

  private static boolean eligible(IdentityCourierClient.Courier courier) {
    return Boolean.TRUE.equals(courier.active()) && "ACTIVE".equalsIgnoreCase(courier.accountStatus());
  }

  public AssignmentRepository.Assignment assign(UUID parcelId, UUID courierId, String subject, String token) {
    validateSubject(subject);
    if (assignments.exists(parcelId)) throw DispatchException.assigned();
    return parcels.withEligibleParcel(parcelId, token, parcel -> {
      if (parcel == null || !parcelId.equals(parcel.parcelId())) throw invalidSource();
      IdentityCourierClient.Courier courier;
      try {
        courier = identity.requireEligible(courierId, token);
      } catch (ResponseStatusException error) {
        String code = switch (error.getStatusCode().value()) {
          case 409 -> "COURIER_INELIGIBLE";
          case 404 -> "COURIER_NOT_FOUND";
          default -> "COURIER_VERIFICATION_FAILED";
        };
        throw new DispatchException(HttpStatus.valueOf(error.getStatusCode().value()), code, error.getReason());
      }
      if (!eligible(courier)) {
        throw new DispatchException(HttpStatus.CONFLICT, "COURIER_INELIGIBLE",
            "This courier is no longer active or available for assignment.");
      }
      if (!isServiceAreaEligible(courier, parcel)) {
        throw new DispatchException(HttpStatus.CONFLICT, "COURIER_INELIGIBLE",
            "Courier service area does not match delivery destination.");
      }
      if (isWorkloadExceeded(courier.id())) {
        throw new DispatchException(HttpStatus.CONFLICT, "COURIER_INELIGIBLE",
            "Courier has reached maximum assignment workload capacity.");
      }
      return assignments.insert(parcelId, courier, subject);
    });
  }

  public AssignmentRepository.Assignment reassign(UUID parcelId, UUID courierId, String subject, String token) {
    validateSubject(subject);
    if (!assignments.exists(parcelId)) {
      throw new DispatchException(HttpStatus.NOT_FOUND, "DELIVERY_NOT_ASSIGNED",
          "The delivery has no active assignment.");
    }
    return parcels.withAssignedParcel(parcelId, token, parcel -> {
      IdentityCourierClient.Courier courier;
      try {
        courier = identity.requireEligible(courierId, token);
      } catch (ResponseStatusException error) {
        String code = switch (error.getStatusCode().value()) {
          case 409 -> "COURIER_INELIGIBLE";
          case 404 -> "COURIER_NOT_FOUND";
          default -> "COURIER_VERIFICATION_FAILED";
        };
        throw new DispatchException(HttpStatus.valueOf(error.getStatusCode().value()), code, error.getReason());
      }
      return assignments.reassign(parcelId, courier, subject);
    });
  }

  public void cancel(UUID parcelId, String subject, String token) {
    validateSubject(subject);
    parcels.withAssignedParcel(parcelId, token, parcel -> {
      assignments.cancel(parcelId, subject);
      return null;
    });
  }

  private static void validateSubject(String subject) {
    if (subject == null || subject.isBlank() || subject.length() > 255) {
      throw new DispatchException(HttpStatus.UNAUTHORIZED, "INVALID_SUBJECT",
          "A valid dispatcher subject is required.");
    }
  }
}
