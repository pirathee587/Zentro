package com.zentro.parcelpricing.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zentro.parcelpricing.grpc.service.ParcelPricingGrpcService;
import com.zentro.parcelpricing.parcel.repository.*;
import com.zentro.parcelpricing.parcel.service.ParcelStatusService;
import com.zentro.parcelpricing.parcel.entity.*;
import com.zentro.contracts.parcel.v1.*;
import java.time.OffsetDateTime;
import java.util.*;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PublicTrackingHistoryRegressionTest {
  @Test void publicHistoryUsesRealRowIdsAndNeverExposesInternalNotes() {
    var bookings=mock(ParcelBookingRepository.class);var history=mock(ParcelStatusRepository.class);
    var p=mock(ParcelBooking.class);var id=UUID.randomUUID();var event=UUID.randomUUID();
    when(p.id()).thenReturn(id);when(p.trackingRef()).thenReturn("ZT-7K3QX9M2PA");when(p.status()).thenReturn(ParcelStatus.PICKED_UP);
    when(bookings.findPublicReference("ZT-7K3QX9M2PA")).thenReturn(Optional.of(p));
    when(history.findHistoryByParcelId(id)).thenReturn(List.of(new ParcelStatusRepository.StatusHistoryRow(event,"CREATED","PICKED_UP","private reason","private customer note","COURIER",OffsetDateTime.parse("2026-11-21T11:00:00Z"))));
    var service=new ParcelPricingGrpcService(bookings,mock(ParcelStatusService.class),null,null,history);
    @SuppressWarnings("unchecked") StreamObserver<PublicTrackingResponse> observer=mock(StreamObserver.class);
    service.getPublicTracking(PublicTrackingRequest.newBuilder().setReference(p.trackingRef()).build(),observer);
    var result=ArgumentCaptor.forClass(PublicTrackingResponse.class);verify(observer).onNext(result.capture());
    var response=result.getValue();assertThat(response.getStatus()).isEqualTo("PICKED_UP");
    assertThat(response.getEvents(0).getId()).isEqualTo(event.toString());
    assertThat(response.getEvents(0).getNote()).isEmpty();assertThat(response.getEvents(0).getReason()).isEmpty();
    verify(observer).onCompleted();
  }
}
