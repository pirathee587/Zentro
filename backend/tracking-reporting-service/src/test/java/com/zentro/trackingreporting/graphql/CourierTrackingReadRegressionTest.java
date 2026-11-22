package com.zentro.trackingreporting.graphql;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zentro.contracts.parcel.v1.*;
import com.zentro.trackingreporting.graphql.service.*;
import com.zentro.trackingreporting.graphql.endpoint.TrackingGraphQlEndpoint;
import com.zentro.trackingreporting.graphql.dto.DeliveryDetailsDto;
import com.zentro.trackingreporting.tracking.dto.TrackingEventDto;
import com.zentro.trackingreporting.tracking.repository.*;
import com.zentro.trackingreporting.tracking.entity.*;
import com.zentro.trackingreporting.tracking.controller.PublicTrackingController;
import com.zentro.trackingreporting.tracking.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class CourierTrackingReadRegressionTest {
  @Test void publicGraphQlAndRestReadPersistedHistoryWithStableIdsAndDeliveryTimestamp() {
    var s2=mock(ParcelPricingGrpcClient.class);
    String id=UUID.randomUUID().toString();
    when(s2.getPublicTracking("ZT-7K3QX9M2PA")).thenReturn(PublicTrackingResponse.newBuilder()
        .setTrackingRef("ZT-7K3QX9M2PA").setStatus("DELIVERED").setCreatedAt("2026-11-21T10:00:00Z")
        .addEvents(StatusHistoryEntryProto.newBuilder().setId(id).setStatus("DELIVERED").setActorRole("SERVICE").setChangedAt("2026-11-21T12:00:00Z"))
        .build());
    var client=new CustomerBookingClient(s2,mock(DispatchCourierGrpcClient.class));
    var first=client.publicTracking("ZT-7K3QX9M2PA");
    assertThat(first.events().get(0).id().toString()).isEqualTo(id);
    assertThat(client.publicTracking("ZT-7K3QX9M2PA").events()).isEqualTo(first.events());
    assertThat(first.deliveredAt()).isEqualTo("2026-11-21T12:00:00Z");
    var rest=new PublicTrackingController(mock(TrackingService.class),mock(TrackingStreamService.class),client);
    var body=rest.getTracking("ZT-7K3QX9M2PA").getBody();
    assertThat(body.rawStatus()).isEqualTo("DELIVERED");
    assertThat(body.events()).isEqualTo(first.events());
    assertThat(body.timeline().get(0).status()).isEqualTo("completed");
  }
  @Test void nonemptyStaleProjectionDoesNotHideAuthoritativeCustomerHistory() {
    var parcels=mock(TrackingParcelRepository.class);var events=mock(TrackingEventRepository.class);
    var bookings=mock(CustomerBookingClient.class);
    var endpoint=new TrackingGraphQlEndpoint(parcels,events,mock(com.zentro.trackingreporting.security.DeliveryAuthorizationService.class),bookings);
    var jwt=Jwt.withTokenValue("test").header("alg","none").subject("fixture-owner").build();
    var auth=new JwtAuthenticationToken(jwt,List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    var p=new TrackingParcel();p.setTrackingRef("ZT-7K3QX9M2PA");p.setStatus(TrackingStatus.OUT_FOR_DELIVERY);
    var delivery=DeliveryDetailsDto.from(p);
    var stale=new TrackingEvent(p.getTrackingRef(),TrackingStatus.CREATED,"Created",null,Instant.parse("2026-11-21T10:00:00Z"));
    when(events.findByTrackingRefInOrderByOccurredAtAsc(List.of(p.getTrackingRef()))).thenReturn(List.of(stale));
    var latest=new TrackingEventDto(UUID.randomUUID(),p.getTrackingRef(),"OUT_FOR_DELIVERY","Out for Delivery",null,"COURIER",null,Instant.parse("2026-11-21T12:00:00Z"),null);
    delivery=delivery.withEvents(List.of(latest));
    assertThat(endpoint.events(List.of(delivery),auth).get(delivery)).containsExactly(latest);
    verifyNoInteractions(bookings);
  }
}
