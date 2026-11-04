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
  }
}
