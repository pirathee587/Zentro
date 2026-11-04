package com.zentro.parcelpricing.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single definition of allowed parcel status transitions.
 *
 * <pre>
 * CREATED          -> PICKED_UP
 * PICKED_UP        -> IN_TRANSIT | FAILED
 * IN_TRANSIT       -> OUT_FOR_DELIVERY | FAILED
 * OUT_FOR_DELIVERY -> DELIVERED | FAILED
 * DELIVERED, FAILED: terminal
 * </pre>
 *
 * Moving to the current status is not a transition, so {@link #isAllowed} returns false for it;
 * callers treat such a request as an idempotent repeat. Role-specific restrictions are added on top
 * of these rules elsewhere and never widen them.
 */
public final class ParcelLifecycle {
  private static final Map<ParcelStatus, Set<ParcelStatus>> ALLOWED;

  static {
    Map<ParcelStatus, Set<ParcelStatus>> rules = new EnumMap<>(ParcelStatus.class);
    rules.put(ParcelStatus.CREATED, EnumSet.of(ParcelStatus.PICKED_UP));
    rules.put(ParcelStatus.PICKED_UP, EnumSet.of(ParcelStatus.IN_TRANSIT, ParcelStatus.FAILED));
    rules.put(ParcelStatus.IN_TRANSIT, EnumSet.of(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED));
    rules.put(ParcelStatus.OUT_FOR_DELIVERY, EnumSet.of(ParcelStatus.DELIVERED, ParcelStatus.FAILED));
    rules.put(ParcelStatus.DELIVERED, EnumSet.noneOf(ParcelStatus.class));
    rules.put(ParcelStatus.FAILED, EnumSet.noneOf(ParcelStatus.class));
    rules.replaceAll((status, next) -> Collections.unmodifiableSet(next));
    ALLOWED = Collections.unmodifiableMap(rules);
  }

  private ParcelLifecycle() {}

  /** Statuses a parcel may move to from {@code status}; empty for terminal statuses. */
  public static Set<ParcelStatus> allowedNext(ParcelStatus status) {
    return ALLOWED.get(Objects.requireNonNull(status, "status"));
  }

  /** True only for a real transition defined above; false for the same status or any other jump. */
  public static boolean isAllowed(ParcelStatus from, ParcelStatus to) {
    Objects.requireNonNull(to, "to");
    return allowedNext(from).contains(to);
  }

  /** DELIVERED and FAILED accept no further changes. */
  public static boolean isTerminal(ParcelStatus status) {
    return allowedNext(status).isEmpty();
  }
}
