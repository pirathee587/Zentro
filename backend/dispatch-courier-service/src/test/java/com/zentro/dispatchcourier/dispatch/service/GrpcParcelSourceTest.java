package com.zentro.dispatchcourier.dispatch.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.zentro.contracts.parcel.v1.ParcelResponse;
import com.zentro.dispatchcourier.courier.service.ParcelPricingGrpcClient;
import com.zentro.dispatchcourier.exception.DispatchException;
import io.grpc.Status;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GrpcParcelSourceTest {
  private final ParcelPricingGrpcClient client = mock(ParcelPricingGrpcClient.class);
  private final GrpcParcelSource source = new GrpcParcelSource(client);

  @Test
  void checksS2StatusBeforeRunningTheAssignmentCallback() {
    UUID parcelId = UUID.randomUUID();
    when(client.getParcel(parcelId)).thenReturn(ParcelResponse.newBuilder()
        .setParcelId(parcelId.toString()).setTrackingNumber("TRK-1").setStatus("CREATED").build());

    String result = source.withEligibleParcel(parcelId, "ignored", parcel -> {
      assertThat(parcel.parcelId()).isEqualTo(parcelId);
      assertThat(parcel.trackingReference()).isEqualTo("TRK-1");
      return "assigned";
    });

    assertThat(result).isEqualTo("assigned");
  }

  @Test
  void rejectsNonCreatedParcels() {
    UUID parcelId = UUID.randomUUID();
    when(client.getParcel(parcelId)).thenReturn(ParcelResponse.newBuilder()
        .setParcelId(parcelId.toString()).setStatus("DELIVERED").build());

    assertThatThrownBy(() -> source.withEligibleParcel(parcelId, "ignored", parcel -> "unexpected"))
        .isInstanceOf(DispatchException.class)
        .satisfies(error -> {
          assertThat(((DispatchException) error).status()).isEqualTo(HttpStatus.CONFLICT);
          assertThat(((DispatchException) error).code()).isEqualTo("PARCEL_NOT_ELIGIBLE");
        });
  }

  @Test
  void mapsMissingParcelAndUnavailableS2ToSafeHttpErrors() {
    UUID parcelId = UUID.randomUUID();
    when(client.getParcel(parcelId)).thenThrow(Status.NOT_FOUND.asRuntimeException());
    assertThatThrownBy(() -> source.withEligibleParcel(parcelId, "ignored", parcel -> "unexpected"))
        .isInstanceOf(DispatchException.class)
        .satisfies(error -> assertThat(((DispatchException) error).status()).isEqualTo(HttpStatus.NOT_FOUND));

    reset(client);
    when(client.getParcel(parcelId)).thenThrow(Status.UNAVAILABLE.asRuntimeException());
    assertThatThrownBy(() -> source.withEligibleParcel(parcelId, "ignored", parcel -> "unexpected"))
        .isInstanceOf(DispatchException.class)
        .satisfies(error -> assertThat(((DispatchException) error).status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
  }

  @Test
  void doesNotInventACompleteQueueWithoutAnS2ListingContract() {
    var result = source.eligibleParcels("ignored");
    assertThat(result.parcels()).isEmpty();
    assertThat(result.complete()).isFalse();
  }
}
