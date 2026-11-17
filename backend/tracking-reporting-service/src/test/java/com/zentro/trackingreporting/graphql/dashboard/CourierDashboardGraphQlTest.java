package com.zentro.trackingreporting.graphql.dashboard;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zentro.trackingreporting.graphql.config.GraphQlConfig;
import com.zentro.trackingreporting.graphql.endpoint.TrackingGraphQlEndpoint;
import com.zentro.trackingreporting.graphql.exception.GraphQlExceptionResolver;
import com.zentro.trackingreporting.security.DeliveryAuthorizationService;
import com.zentro.trackingreporting.tracking.entity.*;
import com.zentro.trackingreporting.tracking.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

@GraphQlTest(controllers = {CourierDashboardGraphQlEndpoint.class, TrackingGraphQlEndpoint.class})
@Import({CourierDashboardService.class, GraphQlConfig.class, GraphQlExceptionResolver.class,
    CourierDashboardExceptionResolver.class, DeliveryAuthorizationService.class})
class CourierDashboardGraphQlTest {
  @Autowired GraphQlTester graph;
  @MockBean CourierAssignmentSnapshotClient snapshots;
  @MockBean com.zentro.trackingreporting.graphql.service.CustomerBookingClient bookings;
  @MockBean TrackingParcelRepository parcels;
  @MockBean TrackingEventRepository events;
  @MockBean JwtDecoder decoder;
  @MockBean CourierEventProjectionRepository eventProjections;
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test void returnsCanonicalParcelIdWithIndependentTrackingRowIdAndExistingQueryStillWorks() {
    UUID parcelId = UUID.randomUUID(), trackingId = UUID.randomUUID();
    var parcel = new TrackingParcel(); parcel.setId(trackingId); parcel.setParcelId(parcelId);
    parcel.setTrackingRef("ZT-SYNTHETIC1"); parcel.setStatus(TrackingStatus.IN_TRANSIT);
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(parcel));
    when(parcels.findByTrackingRef("ZT-SYNTHETIC1")).thenReturn(Optional.of(parcel));
    when(snapshots.snapshot(anyString())).thenReturn(List.of(new CourierAssignmentSnapshotClient.Assignment(
        parcelId, Instant.parse("2026-11-01T00:00:00Z"), "ASSIGNED", null, null, null)));
    SecurityContextHolder.getContext().setAuthentication(CourierDashboardServiceTest.auth("COURIER", "CUSTOMER"));
    graph.document("""
        query {
          courierTodayDashboard(page: 1, size: 10) {
            counts { assigned delivered failed remaining }
            deliveries { parcelId status projectionAvailable }
          }
          publicTracking(trackingRef: "ZT-SYNTHETIC1") { trackingRef status }
        }
        """).execute().errors().verify()
        .path("courierTodayDashboard.counts.assigned").entity(Integer.class).isEqualTo(1)
        .path("courierTodayDashboard.deliveries[0].parcelId").entity(String.class).isEqualTo(parcelId.toString())
        .path("publicTracking.trackingRef").entity(String.class).isEqualTo("ZT-SYNTHETIC1");
  }
  @Test void anonymousDashboardFieldIsDenied() {
    graph.document("{ courierTodayDashboard { counts { assigned } } }").execute().errors()
        .satisfy(errors -> assertThat(errors).anySatisfy(e -> assertThat(e.getErrorType().toString()).isEqualTo("UNAUTHORIZED")));
    verifyNoInteractions(snapshots);
  }
  @Test void dependencyFailureHasSafeCodeAndNoPrivatePayload() {
    SecurityContextHolder.getContext().setAuthentication(CourierDashboardServiceTest.auth("COURIER"));
    when(snapshots.snapshot(anyString())).thenThrow(new CourierDashboardException("ASSIGNMENT_SNAPSHOT_UNAVAILABLE"));
    graph.document("{ courierTodayDashboard { counts { assigned } } }").execute().errors()
        .satisfy(errors -> assertThat(errors).anySatisfy(e ->
            assertThat(e.getExtensions().get("code")).isEqualTo("ASSIGNMENT_SNAPSHOT_UNAVAILABLE")));
  }
  @Test void dispatcherWithoutCourierRoleCannotReadDashboard() {
    SecurityContextHolder.getContext().setAuthentication(CourierDashboardServiceTest.auth("DISPATCHER"));
    graph.document("{ courierTodayDashboard { counts { assigned } } }").execute().errors()
        .satisfy(errors -> assertThat(errors).anySatisfy(e -> assertThat(e.getErrorType().toString()).isEqualTo("FORBIDDEN")));
    verifyNoInteractions(snapshots, parcels, events);
  }
  @Test void reassignmentDuringReadReturnsNoRecipientData() {
    UUID parcelId = UUID.randomUUID(); var parcel = new TrackingParcel(); parcel.setParcelId(parcelId);
    parcel.setStatus(TrackingStatus.IN_TRANSIT); parcel.setRecipientName("Synthetic private recipient");
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(parcel));
    when(snapshots.snapshot(anyString())).thenReturn(List.of(new CourierAssignmentSnapshotClient.Assignment(
        parcelId, Instant.parse("2026-11-01T00:00:00Z"), "ASSIGNED", null, null, null)), List.of());
    SecurityContextHolder.getContext().setAuthentication(CourierDashboardServiceTest.auth("COURIER"));
    graph.document("{ courierTodayDashboard { deliveries { recipient { name } } } }").execute()
        .errors().satisfy(errors -> assertThat(errors).anySatisfy(e ->
            assertThat(e.getExtensions().get("code")).isEqualTo("ASSIGNMENTS_CHANGED")))
        .path("courierTodayDashboard").valueIsNull();
  }
  @Test void mutationRemainsUnsupported() {
    graph.document("mutation { courierTodayDashboard { counts { assigned } } }").execute().errors()
        .satisfy(errors -> assertThat(errors).isNotEmpty());
  }
  @Test void actualFrontendOperationMatchesSchemaAndSerializesAllSelectedFields() throws Exception {
    var source = java.nio.file.Files.readString(java.nio.file.Path.of(
        "../../frontend/zentro-web/src/portals/courier/api/courierDashboardApi.ts"));
    var operation = java.util.regex.Pattern.compile("courierTodayQuery = `([^`]+)`", java.util.regex.Pattern.DOTALL).matcher(source);
    assertThat(operation.find()).isTrue();
    UUID parcelId = UUID.randomUUID(), proofId = UUID.randomUUID();
    var parcel = new TrackingParcel(); parcel.setId(UUID.randomUUID()); parcel.setParcelId(parcelId);
    parcel.setTrackingRef("ZT-SYNTHETIC1"); parcel.setStatus(TrackingStatus.IN_TRANSIT);
    parcel.setRecipientName("Synthetic recipient"); parcel.setRecipientPhone("0000000000");
    parcel.setRecipientAddress("Synthetic address"); parcel.setRecipientCity("Synthetic city");
    parcel.setWeightKg(new java.math.BigDecimal("2.50")); parcel.setSize("MEDIUM");
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of(parcel));
    when(snapshots.snapshot(anyString())).thenReturn(List.of(new CourierAssignmentSnapshotClient.Assignment(
        parcelId, Instant.parse("2026-11-01T00:00:00Z"), "ASSIGNED", proofId, "PENDING", null)));
    SecurityContextHolder.getContext().setAuthentication(CourierDashboardServiceTest.auth("COURIER"));
    graph.document(operation.group(1)).operationName("CourierTodayDashboard").variable("page", 1).variable("size", 10)
        .execute().errors().verify()
        .path("courierTodayDashboard.currentDelivery.parcelId").entity(String.class).isEqualTo(parcelId.toString())
        .path("courierTodayDashboard.deliveries[0].recipient.name").entity(String.class).isEqualTo("Synthetic recipient")
        .path("courierTodayDashboard.deliveries[0].proofId").entity(String.class).isEqualTo(proofId.toString())
        .path("courierTodayDashboard.deliveries[0].completionState").entity(String.class).isEqualTo("PENDING")
        .path("courierTodayDashboard.deliveries[0].weightKg").entity(Double.class).isEqualTo(2.5)
        .path("courierTodayDashboard.counts.delivered").entity(Integer.class).isEqualTo(0);
  }
  @Test void eventOnlyProjectionReturnsRealStatusAndReferenceWithoutInventingContactFields() {
    UUID id=UUID.randomUUID();var p=new TrackingParcel();p.setParcelId(id);p.setTrackingRef("ZT-EVENTONLY");p.setWeightKg(null);p.setStatus(TrackingStatus.PICKED_UP);
    when(snapshots.snapshot(anyString())).thenReturn(List.of(new CourierAssignmentSnapshotClient.Assignment(id,Instant.parse("2026-11-01T00:00:00Z"),"ASSIGNED",null,null,null)));
    when(parcels.findByParcelIdIn(any())).thenReturn(List.of());when(eventProjections.find(any())).thenReturn(List.of(p));
    SecurityContextHolder.getContext().setAuthentication(CourierDashboardServiceTest.auth("COURIER"));
    graph.document("{ courierTodayDashboard { counts { assigned remaining } deliveries { parcelId trackingRef status recipient { name } weightKg projectionAvailable } } }").execute().errors().verify()
      .path("courierTodayDashboard.deliveries[0].trackingRef").entity(String.class).isEqualTo("ZT-EVENTONLY")
      .path("courierTodayDashboard.deliveries[0].status").entity(String.class).isEqualTo("PICKED_UP")
      .path("courierTodayDashboard.deliveries[0].recipient").valueIsNull()
      .path("courierTodayDashboard.deliveries[0].weightKg").valueIsNull();
  }

}
