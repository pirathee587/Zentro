package com.zentro.trackingreporting.graphql.dashboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zentro.trackingreporting.graphql.dashboard.CourierAssignmentSnapshotClient.Assignment;
import com.zentro.trackingreporting.tracking.entity.*;
import com.zentro.trackingreporting.tracking.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CourierDashboardServiceTest {
  final CourierAssignmentSnapshotClient snapshots = mock(CourierAssignmentSnapshotClient.class);
  final TrackingParcelRepository parcels = mock(TrackingParcelRepository.class);
  final TrackingEventRepository events = mock(TrackingEventRepository.class);
  final Instant now = Instant.parse("2026-11-16T10:00:00Z");
  final CourierDashboardService service = new CourierDashboardService(snapshots, parcels, events, Clock.fixed(now, ZoneOffset.UTC));
  final JwtAuthenticationToken courier = auth("COURIER");

  static JwtAuthenticationToken auth(String... roles) {
    return new JwtAuthenticationToken(Jwt.withTokenValue("synthetic-courier").header("alg", "synthetic")
        .subject("keycloak-subject-distinct-from-all-uuids").build(), Arrays.stream(roles)
        .map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
  }
  Assignment assignment(UUID id, String assigned) {
    return new Assignment(id, Instant.parse(assigned), "ASSIGNED", UUID.randomUUID(), "PENDING", null);
  }
  TrackingParcel projection(UUID id, TrackingStatus status) {
    var parcel = new TrackingParcel(); parcel.setId(UUID.randomUUID()); parcel.setParcelId(id);
    parcel.setCourierSubject("stale-other-courier-profile-uuid"); parcel.setStatus(status);
    parcel.setTrackingRef("ZT-" + id.toString().substring(0, 8));
    parcel.setRecipientName("Synthetic recipient"); parcel.setRecipientPhone("synthetic-phone");
    parcel.setRecipientAddress("Synthetic address"); return parcel;
  }
  @Test void totalsCoverPopulationAndCarryoverWhileCurrentPrefersOutForDeliveryAcrossPages() {
    UUID transit = UUID.randomUUID(), out = UUID.randomUUID(), delivered = UUID.randomUUID(), failed = UUID.randomUUID();
    var assignments = List.of(assignment(transit, "2026-11-13T10:00:00Z"), assignment(out, "2026-11-15T10:00:00Z"),
        assignment(delivered, "2026-11-15T11:00:00Z"), assignment(failed, "2026-11-15T12:00:00Z"));
    var done = projection(delivered, TrackingStatus.DELIVERED); done.setDeliveredAt(now.minusSeconds(60));
    when(snapshots.snapshot(anyString())).thenReturn(assignments);
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(projection(transit, TrackingStatus.IN_TRANSIT),
        projection(out, TrackingStatus.OUT_FOR_DELIVERY), done, projection(failed, TrackingStatus.FAILED)));
    var failedEvent = new TrackingEvent(); failedEvent.setEventId(UUID.randomUUID()); failedEvent.setParcelId(failed);
    failedEvent.setStatus(TrackingStatus.FAILED); failedEvent.setOccurredAt(now.minusSeconds(120));
    when(events.findByParcelIdInAndOccurredAtBetweenOrderByOccurredAtDesc(any(), any(), any())).thenReturn(List.of(failedEvent));
    var result = service.dashboard(1, 1, auth("COURIER", "CUSTOMER", "DISPATCHER"));
    assertThat(result.businessDate()).isEqualTo("2026-11-16"); assertThat(result.timeZone()).isEqualTo("Asia/Colombo");
    assertThat(result.counts()).isEqualTo(new CourierTodayDashboardDto.Counts(4, 1, 1, 2));
    assertThat(result.deliveries()).hasSize(1); assertThat(result.pageInfo().totalElements()).isEqualTo(4);
    assertThat(result.currentDelivery().parcelId()).isEqualTo(out);
    assertThat(result.deliveries().getFirst().parcelId()).isEqualTo(transit);
    assertThat(result.deliveries().getFirst().completionState()).isEqualTo("PENDING");
    verify(snapshots, times(2)).snapshot("synthetic-courier");
    verify(parcels).findByParcelIdIn(assignments.stream().map(Assignment::parcelId).toList());
  }
  @Test void emptyAssignmentsDoNotReadProjections() {
    when(snapshots.snapshot(anyString())).thenReturn(List.of());
    var result = service.dashboard(1, 10, courier);
    assertThat(result.counts()).isEqualTo(new CourierTodayDashboardDto.Counts(0, 0, 0, 0));
    assertThat(result.currentDelivery()).isNull(); assertThat(result.deliveries()).isEmpty(); verifyNoInteractions(parcels, events);
  }
  @Test void missingProjectionDoesNotInventStatusRecipientOrDeliveryCompletion() {
    UUID id = UUID.randomUUID(); when(snapshots.snapshot(anyString())).thenReturn(List.of(assignment(id, "2026-11-14T10:00:00Z")));
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of());
    var result = service.dashboard(1, 10, courier); var row = result.deliveries().getFirst();
    assertThat(row.parcelId()).isEqualTo(id); assertThat(row.projectionAvailable()).isFalse();
    assertThat(row.status()).isNull(); assertThat(row.recipient()).isNull(); assertThat(result.currentDelivery()).isNull();
    assertThat(result.counts().remaining()).isEqualTo(1); assertThat(result.counts().delivered()).isZero();
  }
  @Test void terminalRowsWithoutReliableTodayTimestampAreExcluded() {
    UUID id = UUID.randomUUID(); when(snapshots.snapshot(anyString())).thenReturn(List.of(assignment(id, "2026-11-14T10:00:00Z")));
    var old = projection(id, TrackingStatus.FAILED); when(parcels.findByParcelIdIn(any())).thenReturn(List.of(old));
    when(events.findByParcelIdInAndOccurredAtBetweenOrderByOccurredAtDesc(any(), any(), any())).thenReturn(List.of());
    assertThat(service.dashboard(1, 10, courier).counts().assigned()).isZero();
  }
  @Test void colomboMidnightAndAssignmentTimestampBoundTerminalPopulation() {
    UUID oldId = UUID.randomUUID(), todayId = UUID.randomUUID();
    var old = projection(oldId, TrackingStatus.DELIVERED); old.setDeliveredAt(Instant.parse("2026-11-15T18:29:59Z"));
    var today = projection(todayId, TrackingStatus.DELIVERED); today.setDeliveredAt(Instant.parse("2026-11-15T18:30:00Z"));
    when(snapshots.snapshot(anyString())).thenReturn(List.of(assignment(oldId, "2026-11-14T10:00:00Z"),
        assignment(todayId, "2026-11-14T10:00:00Z")));
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(old, today));
    when(events.findByParcelIdInAndOccurredAtBetweenOrderByOccurredAtDesc(any(), any(), any())).thenReturn(List.of());
    assertThat(service.dashboard(1, 10, courier).counts()).isEqualTo(new CourierTodayDashboardDto.Counts(1, 1, 0, 0));
  }
  @Test void cancellationDuringProjectionReadFailsClosed() {
    UUID id = UUID.randomUUID(); when(snapshots.snapshot(anyString()))
        .thenReturn(List.of(assignment(id, "2026-11-15T10:00:00Z")), List.of());
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(projection(id, TrackingStatus.IN_TRANSIT)));
    assertThatThrownBy(() -> service.dashboard(1, 10, courier)).isInstanceOfSatisfying(CourierDashboardException.class,
        e -> assertThat(e.code()).isEqualTo("ASSIGNMENTS_CHANGED"));
  }
  @Test void reassignmentAwayAndBackWithNewTimestampFailsClosed() {
    UUID id = UUID.randomUUID(); when(snapshots.snapshot(anyString()))
        .thenReturn(List.of(assignment(id, "2026-11-15T10:00:00Z")), List.of(assignment(id, "2026-11-16T09:00:00Z")));
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(projection(id, TrackingStatus.IN_TRANSIT)));
    assertThatThrownBy(() -> service.dashboard(1, 10, courier)).isInstanceOf(CourierDashboardException.class);
  }
  @Test void nextDeliveryUsesOldestAssignmentThenCanonicalParcelIdAndLargePageDoesNotOverflow() {
    UUID first = UUID.fromString("11111111-1111-4111-8111-111111111111"), second = UUID.fromString("22222222-2222-4222-8222-222222222222");
    when(snapshots.snapshot(anyString())).thenReturn(List.of(assignment(second, "2026-11-15T10:00:00Z"), assignment(first, "2026-11-15T10:00:00Z")));
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(projection(first, TrackingStatus.PICKED_UP), projection(second, TrackingStatus.IN_TRANSIT)));
    var result = service.dashboard(Integer.MAX_VALUE, 999, courier);
    assertThat(result.deliveries()).isEmpty(); assertThat(result.pageInfo().size()).isEqualTo(50);
    assertThat(result.currentDelivery().parcelId()).isEqualTo(first);
  }
  @Test void unauthenticatedAndWrongRolesNeverReadDependencies() {
    assertThatThrownBy(() -> service.dashboard(1, 10, null)).isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    assertThatThrownBy(() -> service.dashboard(1, 10, auth("DISPATCHER"))).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.dashboard(1, 10, auth("COURIER", "SERVICE"))).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(snapshots, parcels, events);
  }
  @Test void invalidPaginationNeverReadsDependencies() {
    assertThatThrownBy(() -> service.dashboard(0, 10, courier)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.dashboard(1, -1, courier)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(snapshots, parcels, events);
  }
  @Test void failedOwnershipLookupNeverReadsPrivateProjections() {
    when(snapshots.snapshot(anyString())).thenThrow(new CourierDashboardException("COURIER_PROFILE_NOT_FOUND"));
    assertThatThrownBy(() -> service.dashboard(1, 10, courier)).isInstanceOf(CourierDashboardException.class);
    verifyNoInteractions(parcels, events);
  }
}
