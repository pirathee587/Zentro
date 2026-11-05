package com.zentro.parcelpricing.parcel.entity;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The single definition of allowed parcel status transitions and role-specific restrictions (ZD-13, ZD-25).
 *
 * <pre>
 * General Lifecycle:
 * CREATED          -> PICKED_UP
 * PICKED_UP        -> IN_TRANSIT | FAILED
 * IN_TRANSIT       -> OUT_FOR_DELIVERY | FAILED
 * OUT_FOR_DELIVERY -> DELIVERED | FAILED
 * DELIVERED, FAILED: terminal
 *
 * Role Restrictions:
 * - DISPATCHER: All valid transitions above.
 * - SERVICE: Only OUT_FOR_DELIVERY -> DELIVERED (and idempotent repeat of DELIVERED).
 * - COURIER: Forbidden from direct S2 status updates (must use S3 POD).
 * </pre>
 *
 * Moving to the current status is not a transition, so {@link #isAllowed} returns false for it;
 * callers treat such a request as an idempotent repeat. Role-specific restrictions narrow these rules.
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

  /**
   * Statuses a parcel may move to from {@code status} for the given actor role.
   * DISPATCHER is allowed all transitions defined by the lifecycle.
   * SERVICE is strictly narrowed to OUT_FOR_DELIVERY -> DELIVERED.
   * Other roles have no allowed transitions on S2.
   */
  public static Set<ParcelStatus> allowedNext(ParcelStatus status, String role) {
    Objects.requireNonNull(status, "status");
    String normalized = normalizeRole(role);
    if ("DISPATCHER".equals(normalized)) {
      return allowedNext(status);
    }
    if ("SERVICE".equals(normalized)) {
      if (status == ParcelStatus.OUT_FOR_DELIVERY) {
        return Collections.singleton(ParcelStatus.DELIVERED);
      }
      return Collections.emptySet();
    }
    return Collections.emptySet();
  }

  /** True only for a real transition defined above; false for the same status or any other jump. */
  public static boolean isAllowed(ParcelStatus from, ParcelStatus to) {
    Objects.requireNonNull(to, "to");
    return allowedNext(from).contains(to);
  }

  /** True only if the transition from {@code from} to {@code to} is allowed for {@code role}. */
  public static boolean isAllowed(ParcelStatus from, ParcelStatus to, String role) {
    Objects.requireNonNull(to, "to");
    return allowedNext(from, role).contains(to);
  }

  /**
   * Checks whether the given role is permitted to target the given status (either via transition or repeat).
   * DISPATCHER may target any status. SERVICE may target only DELIVERED. Other roles may not target any status.
   */
  public static boolean isRolePermitted(ParcelStatus target, String role) {
    if (target == null) {
      return false;
    }
    String normalized = normalizeRole(role);
    if ("DISPATCHER".equals(normalized)) {
      return true;
    }
    if ("SERVICE".equals(normalized)) {
      return target == ParcelStatus.DELIVERED;
    }
    return false;
  }

  /** DELIVERED and FAILED accept no further changes. */
  public static boolean isTerminal(ParcelStatus status) {
    return allowedNext(status).isEmpty();
  }

  public static String normalizeRole(String role) {
    if (role == null) {
      return "";
    }
    String normalized = role.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("ROLE_")) {
      normalized = normalized.substring("ROLE_".length());
    }
    return normalized;
  }
}
