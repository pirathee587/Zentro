package com.zentro.parcelpricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ParcelLifecycleTest {
  /** The approved transitions, written out independently of the class under test. */
  private static final Set<String> EXPECTED_ALLOWED = Set.of(
      "CREATED->PICKED_UP",
      "PICKED_UP->IN_TRANSIT",
      "PICKED_UP->FAILED",
      "IN_TRANSIT->OUT_FOR_DELIVERY",
      "IN_TRANSIT->FAILED",
      "OUT_FOR_DELIVERY->DELIVERED",
      "OUT_FOR_DELIVERY->FAILED");

  static Stream<Arguments> allPairs() {
    List<Arguments> pairs = new ArrayList<>();
    for (ParcelStatus from : ParcelStatus.values()) {
      for (ParcelStatus to : ParcelStatus.values()) {
        pairs.add(Arguments.of(from, to, EXPECTED_ALLOWED.contains(from + "->" + to)));
      }
    }
    return pairs.stream();
  }

  @ParameterizedTest(name = "{0} -> {1} allowed={2}")
  @MethodSource("allPairs")
  @DisplayName("Every from/to pair is allowed or rejected exactly as approved")
  void everyPairMatchesTheApprovedTable(ParcelStatus from, ParcelStatus to, boolean expected) {
    assertThat(ParcelLifecycle.isAllowed(from, to)).isEqualTo(expected);
    assertThat(ParcelLifecycle.allowedNext(from).contains(to)).isEqualTo(expected);
  }

  @Test
  @DisplayName("All 36 pairs are covered and exactly 7 are allowed")
  void pairCountsMatch() {
    assertThat(allPairs().count()).isEqualTo(36);
    assertThat(allPairs().filter(a -> (boolean) a.get()[2]).count()).isEqualTo(7);
  }

  @ParameterizedTest
  @EnumSource(ParcelStatus.class)
  @DisplayName("Repeating the current status is never a transition")
  void sameStatusIsNotATransition(ParcelStatus status) {
    assertThat(ParcelLifecycle.isAllowed(status, status)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(value = ParcelStatus.class, names = {"DELIVERED", "FAILED"})
  @DisplayName("DELIVERED and FAILED are terminal with no next status")
  void terminalStatuses(ParcelStatus status) {
    assertThat(ParcelLifecycle.isTerminal(status)).isTrue();
    assertThat(ParcelLifecycle.allowedNext(status)).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(value = ParcelStatus.class, names = {"DELIVERED", "FAILED"}, mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Every other status has at least one next status")
  void nonTerminalStatuses(ParcelStatus status) {
    assertThat(ParcelLifecycle.isTerminal(status)).isFalse();
    assertThat(ParcelLifecycle.allowedNext(status)).isNotEmpty();
  }

  @Test
  @DisplayName("FAILED is reachable from every active status after pickup, but not from CREATED")
  void failedReachability() {
    assertThat(ParcelLifecycle.isAllowed(ParcelStatus.CREATED, ParcelStatus.FAILED)).isFalse();
    for (ParcelStatus s : EnumSet.of(ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT, ParcelStatus.OUT_FOR_DELIVERY)) {
      assertThat(ParcelLifecycle.isAllowed(s, ParcelStatus.FAILED)).isTrue();
    }
  }

  @Test
  @DisplayName("Skipping a step is rejected")
  void skippingIsRejected() {
    assertThat(ParcelLifecycle.isAllowed(ParcelStatus.CREATED, ParcelStatus.DELIVERED)).isFalse();
    assertThat(ParcelLifecycle.isAllowed(ParcelStatus.PICKED_UP, ParcelStatus.DELIVERED)).isFalse();
    assertThat(ParcelLifecycle.isAllowed(ParcelStatus.CREATED, ParcelStatus.IN_TRANSIT)).isFalse();
  }

  @Test
  @DisplayName("The allowed sets cannot be modified by callers")
  void allowedSetsAreReadOnly() {
    assertThatThrownBy(() -> ParcelLifecycle.allowedNext(ParcelStatus.CREATED).add(ParcelStatus.DELIVERED))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThat(ParcelLifecycle.isAllowed(ParcelStatus.CREATED, ParcelStatus.DELIVERED)).isFalse();
  }

  @Test
  @DisplayName("Null status is rejected")
  void nullIsRejected() {
    assertThatThrownBy(() -> ParcelLifecycle.allowedNext(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ParcelLifecycle.isAllowed(ParcelStatus.CREATED, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ParcelLifecycle.allowedNext(null, "DISPATCHER")).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> ParcelLifecycle.isAllowed(ParcelStatus.CREATED, null, "DISPATCHER"))
        .isInstanceOf(NullPointerException.class);
  }

  static Stream<Arguments> allPairsForDispatcher() {
    return allPairs();
  }

  @ParameterizedTest(name = "DISPATCHER: {0} -> {1} allowed={2}")
  @MethodSource("allPairsForDispatcher")
  @DisplayName("DISPATCHER has the same transitions as the approved general lifecycle table")
  void dispatcherMatchesApprovedTable(ParcelStatus from, ParcelStatus to, boolean expected) {
    assertThat(ParcelLifecycle.isAllowed(from, to, "DISPATCHER")).isEqualTo(expected);
    assertThat(ParcelLifecycle.isAllowed(from, to, "ROLE_DISPATCHER")).isEqualTo(expected);
    assertThat(ParcelLifecycle.isAllowed(from, to, "dispatcher")).isEqualTo(expected);
    assertThat(ParcelLifecycle.allowedNext(from, "DISPATCHER").contains(to)).isEqualTo(expected);
  }

  static Stream<Arguments> allPairsForService() {
    List<Arguments> pairs = new ArrayList<>();
    for (ParcelStatus from : ParcelStatus.values()) {
      for (ParcelStatus to : ParcelStatus.values()) {
        boolean expected = (from == ParcelStatus.OUT_FOR_DELIVERY && to == ParcelStatus.DELIVERED);
        pairs.add(Arguments.of(from, to, expected));
      }
    }
    return pairs.stream();
  }

  @ParameterizedTest(name = "SERVICE: {0} -> {1} allowed={2}")
  @MethodSource("allPairsForService")
  @DisplayName("SERVICE is strictly narrowed to only OUT_FOR_DELIVERY -> DELIVERED")
  void serviceMatchesApprovedTable(ParcelStatus from, ParcelStatus to, boolean expected) {
    assertThat(ParcelLifecycle.isAllowed(from, to, "SERVICE")).isEqualTo(expected);
    assertThat(ParcelLifecycle.isAllowed(from, to, "ROLE_SERVICE")).isEqualTo(expected);
    assertThat(ParcelLifecycle.isAllowed(from, to, "service")).isEqualTo(expected);
    assertThat(ParcelLifecycle.allowedNext(from, "SERVICE").contains(to)).isEqualTo(expected);
  }

  @Test
  @DisplayName("SERVICE has exactly 1 allowed transition across all 36 pairs")
  void servicePairCountMatches() {
    assertThat(allPairsForService().filter(a -> (boolean) a.get()[2]).count()).isEqualTo(1);
    assertThat(ParcelLifecycle.allowedNext(ParcelStatus.OUT_FOR_DELIVERY, "SERVICE"))
        .containsExactly(ParcelStatus.DELIVERED);
    assertThat(ParcelLifecycle.allowedNext(ParcelStatus.CREATED, "SERVICE")).isEmpty();
    assertThat(ParcelLifecycle.allowedNext(ParcelStatus.PICKED_UP, "SERVICE")).isEmpty();
    assertThat(ParcelLifecycle.allowedNext(ParcelStatus.IN_TRANSIT, "SERVICE")).isEmpty();
  }

  @ParameterizedTest
  @EnumSource(ParcelStatus.class)
  @DisplayName("COURIER has no allowed direct transitions on S2")
  void courierHasNoAllowedTransitions(ParcelStatus status) {
    assertThat(ParcelLifecycle.allowedNext(status, "COURIER")).isEmpty();
    assertThat(ParcelLifecycle.allowedNext(status, "ROLE_COURIER")).isEmpty();
    for (ParcelStatus target : ParcelStatus.values()) {
      assertThat(ParcelLifecycle.isAllowed(status, target, "COURIER")).isFalse();
    }
  }

  @Test
  @DisplayName("Unknown or null role has no allowed transitions")
  void unknownRoleHasNoAllowedTransitions() {
    for (ParcelStatus status : ParcelStatus.values()) {
      assertThat(ParcelLifecycle.allowedNext(status, null)).isEmpty();
      assertThat(ParcelLifecycle.allowedNext(status, "")).isEmpty();
      assertThat(ParcelLifecycle.allowedNext(status, "UNKNOWN")).isEmpty();
      assertThat(ParcelLifecycle.allowedNext(status, "CUSTOMER")).isEmpty();
    }
  }

  @Test
  @DisplayName("Role target permissions check matches the approved policy")
  void roleTargetPermissionsCheck() {
    for (ParcelStatus status : ParcelStatus.values()) {
      assertThat(ParcelLifecycle.isRolePermitted(status, "DISPATCHER")).isTrue();
      assertThat(ParcelLifecycle.isRolePermitted(status, "ROLE_DISPATCHER")).isTrue();
      assertThat(ParcelLifecycle.isRolePermitted(status, "COURIER")).isFalse();
      assertThat(ParcelLifecycle.isRolePermitted(status, "CUSTOMER")).isFalse();
      assertThat(ParcelLifecycle.isRolePermitted(status, null)).isFalse();
      if (status == ParcelStatus.DELIVERED) {
        assertThat(ParcelLifecycle.isRolePermitted(status, "SERVICE")).isTrue();
        assertThat(ParcelLifecycle.isRolePermitted(status, "ROLE_SERVICE")).isTrue();
      } else {
        assertThat(ParcelLifecycle.isRolePermitted(status, "SERVICE")).isFalse();
      }
    }
    assertThat(ParcelLifecycle.isRolePermitted(null, "DISPATCHER")).isFalse();
  }

  @Test
  @DisplayName("Role normalization strips whitespace, case, and ROLE_ prefix")
  void roleNormalization() {
    assertThat(ParcelLifecycle.normalizeRole("  ROLE_DISPATCHER ")).isEqualTo("DISPATCHER");
    assertThat(ParcelLifecycle.normalizeRole("service")).isEqualTo("SERVICE");
    assertThat(ParcelLifecycle.normalizeRole(null)).isEmpty();
  }
}
