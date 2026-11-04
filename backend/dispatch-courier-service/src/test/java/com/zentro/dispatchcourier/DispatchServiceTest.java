package com.zentro.dispatchcourier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
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
    when(source.eligibleParcels("token")).thenReturn(new ParcelSource.Snapshot(List.of(parcel), true));
    doAnswer(call -> {
      Function<ParcelSource.Parcel, Object> action = call.getArgument(2);
      return action.apply(parcel);
    }).when(source).withEligibleParcel(eq(parcelId), eq("token"), any());
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

  private static void assertCode(Runnable operation, String code) {
    assertThatThrownBy(operation::run).isInstanceOfSatisfying(DispatchException.class,
        ex -> assertThat(ex.code()).isEqualTo(code));
  }
}
