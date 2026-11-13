package com.zentro.dispatchcourier.dispatch.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.zentro.dispatchcourier.courier.service.IdentityCourierClient;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.exception.DispatchException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class DispatchServiceTest {
  final UUID parcelId = UUID.randomUUID();
  final UUID courierId = UUID.randomUUID();
  final AssignmentRepository assignments = mock(AssignmentRepository.class);
  final IdentityCourierClient identity = mock(IdentityCourierClient.class);
  final ParcelSource source = mock(ParcelSource.class);
  final DispatchService service = new DispatchService(source, assignments, identity);
  final ParcelSource.Parcel parcel = new ParcelSource.Parcel(parcelId, "TRACK-ONE", "Colombo", "Kandy", Instant.parse("2026-10-31T10:00:00Z"));
  final IdentityCourierClient.Courier courier = new IdentityCourierClient.Courier(courierId, UUID.randomUUID(), "Courier", true, "ACTIVE");

  @BeforeEach void setup() {
    when(assignments.assignedIds(any())).thenReturn(Set.of());
    when(assignments.assignmentCounts()).thenReturn(Map.of());
    when(source.eligibleParcels("token")).thenReturn(new ParcelSource.Snapshot(List.of(parcel), true));
    doAnswer(call -> {
      Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      return action.apply(parcel);
    }).when(source).withEligibleParcel(eq(parcelId), eq("token"), any());
    doAnswer(call -> {
      Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      return action.apply(parcel);
    }).when(source).withAssignedParcel(eq(parcelId), eq("token"), any());
  }

  @Test void workloadUsesAssignmentCountsAndIdentityAvailability() {
    var unavailable = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(),
        "Unavailable", false, "ACTIVE");
    when(identity.listAll("token")).thenReturn(List.of(courier, unavailable));
    when(assignments.assignmentCounts()).thenReturn(Map.of(courierId, 3L));

    var result = service.workload("token", "", "");

    assertThat(result.total()).isEqualTo(2);
    assertThat(result.overloadConfigured()).isFalse();
    assertThat(result.items()).extracting(DispatchService.CourierWorkload::assignmentCount)
        .containsExactly(3L, 0L);
    assertThat(result.items()).extracting(DispatchService.CourierWorkload::availability)
        .containsExactly(DispatchService.Availability.AVAILABLE, DispatchService.Availability.UNAVAILABLE);
  }

  @Test void workloadFiltersByAvailabilityAndRejectsInvalidFilters() {
    when(identity.listAll("token")).thenReturn(List.of(courier));
    assertThat(service.workload("token", "", "available").items()).hasSize(1);
    assertThat(service.workload("token", "", "unavailable").items()).isEmpty();
    assertCode(() -> service.workload("token", "", "unknown"), "INVALID_WORKLOAD_FILTER");
    assertCode(() -> service.workload("token", "x".repeat(201), ""), "INVALID_WORKLOAD_FILTER");
  }

  @Test void excludesAuthoritativeAssignmentsAndAppliesSearchAndUtcDates() {
    var assignedId = UUID.randomUUID();
    var old = new ParcelSource.Parcel(UUID.randomUUID(), "OLDER", "Galle", "Matara", Instant.parse("2026-10-30T23:59:59Z"));
    var assigned = new ParcelSource.Parcel(assignedId, "ALREADY", null, null, Instant.now());
    when(source.eligibleParcels("token")).thenReturn(new ParcelSource.Snapshot(List.of(old, parcel, assigned), true));
    when(assignments.assignedIds(any())).thenReturn(Set.of(assignedId));
    var result = service.queue("token", "kAnDy", LocalDate.parse("2026-10-31"), LocalDate.parse("2026-10-31"), "oldest");
    assertThat(result.items()).containsExactly(parcel);
    assertThat(result.total()).isEqualTo(2);
    assertThat(result.matchingTotal()).isEqualTo(1);
    assertThat(service.queue("token", "", null, null, "newest").items()).containsExactly(parcel, old);
  }

  @Test void incompleteSourceNeverClaimsAccurateTotals() {
    when(source.eligibleParcels("token")).thenReturn(new ParcelSource.Snapshot(List.of(parcel), false));
    var queue = service.queue("token", "", null, null, "newest");
    assertThat(queue.total()).isNull(); assertThat(queue.matchingTotal()).isNull();
  }

  @Test void rejectsDuplicateSourceIdsInsteadOfInflatingTotals() {
    when(source.eligibleParcels("token")).thenReturn(new ParcelSource.Snapshot(List.of(parcel, parcel), true));
    assertCode(() -> service.queue("token", "", null, null, "newest"), "INVALID_PARCEL_SOURCE");
  }

  @Test void validatesFiltersBeforeCallingDependencies() {
    assertCode(() -> service.queue("token", "", LocalDate.now(), LocalDate.now().minusDays(1), "newest"), "INVALID_FILTER");
    assertCode(() -> service.queue("token", "x".repeat(201), null, null, "newest"), "INVALID_FILTER");
    assertCode(() -> service.queue("token", "", null, null, "unsupported"), "INVALID_FILTER");
    verifyNoInteractions(source, assignments, identity);
  }

  @Test void assignsOnlyInsideSourceGuardAfterFreshCourierRevalidation() {
    when(identity.requireEligible(courierId, "token")).thenReturn(courier);
    var saved = new AssignmentRepository.Assignment(parcelId, courierId, courier.userId(), "dispatcher", Instant.now());
    when(assignments.insert(parcelId, courier, "dispatcher")).thenReturn(saved);
    assertThat(service.assign(parcelId, courierId, "dispatcher", "token")).isSameAs(saved);
    var order = inOrder(source, identity, assignments);
    order.verify(assignments).exists(parcelId);
    order.verify(source).withEligibleParcel(eq(parcelId), eq("token"), any());
    order.verify(identity).requireEligible(courierId, "token");
    order.verify(assignments).insert(parcelId, courier, "dispatcher");
  }

  @Test void alreadyAssignedNeverCallsRemoteDependencies() {
    when(assignments.exists(parcelId)).thenReturn(true);
    assertCode(() -> service.assign(parcelId, courierId, "dispatcher", "token"), "DELIVERY_ALREADY_ASSIGNED");
    verifyNoInteractions(source, identity);
  }

  @Test void reassignRequiresFreshEligibleCourierAndUsesDispatcherSubject() {
    var replacement = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(),
        "Replacement", true, "ACTIVE");
    var saved = new AssignmentRepository.Assignment(parcelId, replacement.id(), replacement.userId(),
        "dispatcher", Instant.now());
    when(assignments.exists(parcelId)).thenReturn(true);
    when(identity.requireEligible(replacement.id(), "token")).thenReturn(replacement);
    when(assignments.reassign(parcelId, replacement, "dispatcher")).thenReturn(saved);

    assertThat(service.reassign(parcelId, replacement.id(), "dispatcher", "token")).isSameAs(saved);
    verify(assignments).reassign(parcelId, replacement, "dispatcher");
  }

  @Test void reassignRejectsInvalidDispatcherSubjectBeforeRemoteCalls() {
    assertCode(() -> service.reassign(parcelId, courierId, " ", "token"), "INVALID_SUBJECT");
    verifyNoInteractions(identity, assignments);
  }

  @ParameterizedTest @ValueSource(strings = {"inactive", "suspended", "area"})
  void reassignRepeatsAvailabilityAndAreaValidation(String invalid) {
    when(assignments.exists(parcelId)).thenReturn(true);
    var replacement = new IdentityCourierClient.Courier(courierId, UUID.randomUUID(), "Replacement",
        !invalid.equals("inactive"), invalid.equals("suspended") ? "SUSPENDED" : "ACTIVE",
        invalid.equals("area") ? "Galle" : "Kandy");
    when(identity.requireEligible(courierId, "token")).thenReturn(replacement);
    assertCode(() -> service.reassign(parcelId, courierId, "dispatcher", "token"), "COURIER_INELIGIBLE");
    verify(assignments, never()).reassign(any(), any(), any());
  }

  @Test void reassignRejectsMismatchedSourceBeforeLookingUpCourier() {
    when(assignments.exists(parcelId)).thenReturn(true);
    doAnswer(call -> {
      Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      return action.apply(new ParcelSource.Parcel(UUID.randomUUID(), null, null, null, Instant.now()));
    }).when(source).withAssignedParcel(eq(parcelId), eq("token"), any());
    assertCode(() -> service.reassign(parcelId, courierId, "dispatcher", "token"), "INVALID_PARCEL_SOURCE");
    verifyNoInteractions(identity);
    verify(assignments, never()).reassign(any(), any(), any());
  }

  @Test void reassignRejectsMissingAssignmentBeforeCheckingReplacementCourier() {
    assertCode(() -> service.reassign(parcelId, courierId, "dispatcher", "token"), "DELIVERY_NOT_ASSIGNED");
    verifyNoInteractions(identity);
  }

  @Test void cancellationUsesAuthenticatedDispatcherSubject() {
    service.cancel(parcelId, "dispatcher", "token");
    verify(assignments).cancel(parcelId, "dispatcher");
  }

  @ParameterizedTest @ValueSource(ints = {404, 409, 503})
  void sourceFailuresNeverWriteAssignmentsOrCheckCouriers(int status) {
    doThrow(new DispatchException(HttpStatus.valueOf(status), "SOURCE_TEST", "Unavailable"))
        .when(source).withEligibleParcel(eq(parcelId), eq("token"), any());
    assertCode(() -> service.assign(parcelId, courierId, "dispatcher", "token"), "SOURCE_TEST");
    verifyNoInteractions(identity); verify(assignments, never()).insert(any(), any(), any());
  }

  @ParameterizedTest @ValueSource(ints = {404, 409, 503})
  void courierFailureNeverPersists(int status) {
    when(identity.requireEligible(courierId, "token")).thenThrow(new ResponseStatusException(HttpStatus.valueOf(status), "Unavailable"));
    assertThatThrownBy(() -> service.assign(parcelId, courierId, "dispatcher", "token"))
        .isInstanceOfSatisfying(DispatchException.class, ex -> assertThat(ex.status().value()).isEqualTo(status));
    verify(assignments, never()).insert(any(), any(), any());
  }

  @Test void productionSourceFailsClosedWithoutInvokingCallback() {
    var callback = mock(Function.class);
    assertCode(() -> new UnavailableParcelSource().withEligibleParcel(parcelId, "token", callback), "DISPATCH_SOURCE_UNAVAILABLE");
    verifyNoInteractions(callback);
  }

  @Test void productionSourceFailsClosedForQueueAssignmentReassignmentAndCancellation() {
    var production = new DispatchService(new UnavailableParcelSource(), assignments, identity);
    assertCode(() -> production.queue("token", "", null, null, "newest"), "DISPATCH_SOURCE_UNAVAILABLE");
    assertCode(() -> production.assign(parcelId, courierId, "dispatcher", "token"), "DISPATCH_SOURCE_UNAVAILABLE");
    assertCode(() -> production.couriers(parcelId, "token"), "DISPATCH_SOURCE_UNAVAILABLE");
    when(assignments.exists(parcelId)).thenReturn(true);
    assertCode(() -> production.reassign(parcelId, courierId, "dispatcher", "token"), "DISPATCH_SOURCE_UNAVAILABLE");
    assertCode(() -> production.cancel(parcelId, "dispatcher", "token"), "DISPATCH_SOURCE_UNAVAILABLE");
    verifyNoInteractions(identity);
    verify(assignments, never()).insert(any(), any(), any());
    verify(assignments, never()).reassign(any(), any(), any());
    verify(assignments, never()).cancel(any(), any());
  }

  @Test void courierInactiveOrNonActiveAccountStatusRejected() {
    var inactive = new IdentityCourierClient.Courier(courierId, UUID.randomUUID(), "Courier", false, "ACTIVE", "Kandy");
    when(identity.requireEligible(courierId, "token")).thenReturn(inactive);
    assertCode(() -> service.assign(parcelId, courierId, "dispatcher", "token"), "COURIER_INELIGIBLE");
    verify(assignments, never()).insert(any(), any(), any());

    var suspended = new IdentityCourierClient.Courier(courierId, UUID.randomUUID(), "Courier", true, "SUSPENDED", "Kandy");
    when(identity.requireEligible(courierId, "token")).thenReturn(suspended);
    assertCode(() -> service.assign(parcelId, courierId, "dispatcher", "token"), "COURIER_INELIGIBLE");
    verify(assignments, never()).insert(any(), any(), any());
  }

  @Test void courierServiceAreaMismatchRejected() {
    var mismatched = new IdentityCourierClient.Courier(courierId, UUID.randomUUID(), "Courier", true, "ACTIVE", "Galle");
    when(identity.requireEligible(courierId, "token")).thenReturn(mismatched);
    assertCode(() -> service.assign(parcelId, courierId, "dispatcher", "token"), "COURIER_INELIGIBLE");
    verify(assignments, never()).insert(any(), any(), any());
  }

  @Test void courierServiceAreaMatchAccepted() {
    var matched = new IdentityCourierClient.Courier(courierId, UUID.randomUUID(), "Courier", true, "ACTIVE", "Kandy");
    when(identity.requireEligible(courierId, "token")).thenReturn(matched);
    var saved = new AssignmentRepository.Assignment(parcelId, courierId, matched.userId(), "dispatcher", Instant.now());
    when(assignments.insert(parcelId, matched, "dispatcher")).thenReturn(saved);
    assertThat(service.assign(parcelId, courierId, "dispatcher", "token")).isSameAs(saved);
    verify(assignments).insert(parcelId, matched, "dispatcher");
  }

  @Test void courierWorkloadExceededRejected() {
    when(identity.requireEligible(courierId, "token")).thenReturn(courier);
    when(assignments.getAssignmentCount(courierId)).thenReturn(10L);
    assertCode(() -> service.assign(parcelId, courierId, "dispatcher", "token"), "COURIER_INELIGIBLE");
    verify(assignments, never()).insert(any(), any(), any());
  }

  @Test void couriersFiltersByWorkloadAndServiceAreaAndAvailability() {
    var c1 = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "Matching", true, "ACTIVE", "Kandy");
    var c2 = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "WrongZone", true, "ACTIVE", "Colombo");
    var c3 = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "Overloaded", true, "ACTIVE", "Kandy");
    var c4 = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "Inactive", false, "ACTIVE", "Kandy");
    var c5 = new IdentityCourierClient.Courier(UUID.randomUUID(), UUID.randomUUID(), "Unrestricted", true, "ACTIVE", null);

    when(identity.listEligible("token")).thenReturn(List.of(c1, c2, c3, c4, c5));
    when(assignments.assignmentCounts()).thenReturn(Map.of(c3.id(), 10L, c1.id(), 2L));

    var eligible = service.couriers(parcelId, "token");
    assertThat(eligible).containsExactly(c1, c5);
  }

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(DispatchException.class,
        ex -> assertThat(ex.code()).isEqualTo(code));
  }
}
