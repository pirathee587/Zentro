package com.zentro.dispatchcourier;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DispatchService {
  public record Queue(List<ParcelSource.Parcel> items, Long total, Long matchingTotal, boolean complete) {}
  private final ParcelSource parcels;
  private final AssignmentRepository assignments;
  private final IdentityCourierClient identity;

  public DispatchService(ParcelSource parcels, AssignmentRepository assignments, IdentityCourierClient identity) {
    this.parcels = parcels;
    this.assignments = assignments;
    this.identity = identity;
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
      return identity.listEligible(token);
    });
  }

  public AssignmentRepository.Assignment assign(UUID parcelId, UUID courierId, String subject, String token) {
    if (subject == null || subject.isBlank() || subject.length() > 255) {
      throw new DispatchException(HttpStatus.UNAUTHORIZED, "INVALID_SUBJECT", "A valid dispatcher subject is required.");
    }
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
      return assignments.insert(parcelId, courier, subject);
    });
  }
}
