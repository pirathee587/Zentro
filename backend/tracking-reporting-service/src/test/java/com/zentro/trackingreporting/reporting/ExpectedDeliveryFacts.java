package com.zentro.trackingreporting.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/** Expected counts come only from the supplied ledger, never from persisted projections. */
final class ExpectedDeliveryFacts {
  record Fact(UUID eventId, UUID parcelId, String status, Instant occurredAt) {}
  record Counts(long created, long delivered, long failed) {}

  static Fact fact(int event, int parcel, String status, String occurredAt) {
    return new Fact(new UUID(0, event), new UUID(0, parcel), status, Instant.parse(occurredAt));
  }

  static List<Fact> ledger() {
    return List.of(
        fact(1, 101, "CREATED", "2026-11-01T18:29:59Z"),
        fact(2, 101, "OUT_FOR_DELIVERY", "2026-11-02T10:00:00Z"),
        fact(3, 101, "DELIVERED", "2026-11-02T18:29:59Z"),
        fact(4, 102, "CREATED", "2026-11-01T18:30:00Z"),
        fact(5, 102, "FAILED", "2026-11-02T18:30:00Z"),
        fact(6, 103, "CREATED", "2026-11-05T00:00:00Z"));
  }

  static Map<LocalDate, Counts> recount(List<Fact> ledger) {
    var unique = new LinkedHashMap<UUID, Fact>();
    ledger.forEach(fact -> unique.putIfAbsent(fact.eventId(), fact));
    var days = new TreeMap<LocalDate, long[]>();
    unique.values().forEach(fact -> days.computeIfAbsent(day(fact), ignored -> new long[3]));
    var parcels = unique.values().stream().collect(Collectors.groupingBy(Fact::parcelId));
    Comparator<Fact> businessOrder = Comparator.comparing(Fact::occurredAt)
        .thenComparing(fact -> fact.eventId().toString());
    for (var facts : parcels.values()) {
      facts.stream().filter(fact -> fact.status().equals("CREATED")).min(businessOrder)
          .ifPresent(fact -> days.get(day(fact))[0]++);
      facts.stream().filter(fact -> List.of("DELIVERED", "FAILED").contains(fact.status()))
          .min(businessOrder)
          .ifPresent(fact -> days.get(day(fact))[fact.status().equals("DELIVERED") ? 1 : 2]++);
    }
    var result = new TreeMap<LocalDate, Counts>();
    days.forEach((day, counts) -> result.put(day, new Counts(counts[0], counts[1], counts[2])));
    return result;
  }

  private static LocalDate day(Fact fact) {
    return fact.occurredAt().atZone(ZoneId.of("Asia/Colombo")).toLocalDate();
  }
}
