package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.zentro.dispatchcourier.exception.CompletionFailure;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ParcelStatusClientTest {
  private ParcelPricingGrpcClient grpc;
  private ParcelStatusClient client;
  private final UUID parcel = UUID.randomUUID();

  @BeforeEach void setup() {
    grpc = mock(ParcelPricingGrpcClient.class);
    client = new ParcelStatusClient(grpc);
  }

  @Test void completionDelegatesToTheS2GrpcClient() {
    client.complete(parcel);
    verify(grpc).completeDelivery(parcel);
  }

  @Test void completionFailureIsPropagatedWithoutRetryOrProtocolFallback() {
    doThrow(new CompletionFailure("REJECTED", "DELIVERY_TRANSITION_REJECTED"))
        .when(grpc).completeDelivery(parcel);

    assertThatThrownBy(() -> client.complete(parcel)).isInstanceOfSatisfying(CompletionFailure.class,
        error -> assertThat(error.state()).isEqualTo("REJECTED"));
    verify(grpc, times(1)).completeDelivery(parcel);
  }

  @Test void courierStatusDelegatesAllOwnershipAndStatusFieldsToS2() {
    Map<String, String> response = Map.of("parcelId", parcel.toString(), "status", "DELIVERED");
    doReturn(response).when(grpc)
        .updateCourierStatus(parcel, "DELIVERED", "recipient", "left safely", "courier-subject");

    assertThat(client.updateCourierStatus(parcel, "DELIVERED", "recipient", "left safely", "courier-subject"))
        .isSameAs(response);
    verify(grpc).updateCourierStatus(parcel, "DELIVERED", "recipient", "left safely", "courier-subject");
  }
}
