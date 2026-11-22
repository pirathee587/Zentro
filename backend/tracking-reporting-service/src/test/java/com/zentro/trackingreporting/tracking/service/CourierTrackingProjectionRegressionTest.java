package com.zentro.trackingreporting.tracking.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zentro.trackingreporting.tracking.entity.*;
import com.zentro.trackingreporting.tracking.repository.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class CourierTrackingProjectionRegressionTest {
  @Test void delayedCreatedAndPickedUpCannotRegressDeliveredProjectionOrLoseDeliveryTime() {
    var parcels=mock(TrackingParcelRepository.class);var events=mock(TrackingEventRepository.class);
    var service=new TrackingService(parcels,events,mock(TrackingStreamService.class));
    var id=UUID.randomUUID();var p=new TrackingParcel();p.setParcelId(id);p.setTrackingRef("ZT-7K3QX9M2PA");p.setStatus(TrackingStatus.DELIVERED);
    var delivered=new TrackingEvent(p.getTrackingRef(),TrackingStatus.DELIVERED,"Delivered",null,Instant.parse("2026-11-21T12:00:00Z"));
    delivered.setEventId(UUID.randomUUID());
    when(parcels.findByTrackingRef(p.getTrackingRef())).thenReturn(Optional.of(p));
    when(events.save(any())).thenAnswer(i->i.getArgument(0));
    when(events.findByTrackingRefOrderByOccurredAtAsc(p.getTrackingRef())).thenReturn(List.of(delivered));
    for(var status:List.of(TrackingStatus.PICKED_UP,TrackingStatus.CREATED)){
      service.recordEvent(p.getTrackingRef(),id,status,null,"COURIER",null,null,null,null,Instant.parse("2026-11-21T11:00:00Z"),UUID.randomUUID());
      assertThat(p.getStatus()).isEqualTo(TrackingStatus.DELIVERED);
      assertThat(p.getDeliveredAt()).isEqualTo(delivered.getOccurredAt());
    }
  }
  @Test void eventCannotUpdateAnotherParcelUsingTheSameTrackingReference() {
    var parcels=mock(TrackingParcelRepository.class);var events=mock(TrackingEventRepository.class);
    var service=new TrackingService(parcels,events,mock(TrackingStreamService.class));
    var p=new TrackingParcel();p.setParcelId(UUID.randomUUID());p.setTrackingRef("ZT-7K3QX9M2PA");
    when(parcels.findByTrackingRef(p.getTrackingRef())).thenReturn(Optional.of(p));
    assertThatThrownBy(()->service.recordEvent(p.getTrackingRef(),UUID.randomUUID(),TrackingStatus.PICKED_UP,null,"COURIER",null,null,null,null,Instant.now(),UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    verify(parcels,never()).save(any());
  }
}
