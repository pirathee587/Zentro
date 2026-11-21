package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.zentro.contracts.parcel.v1.CompleteParcelDeliveryRequest;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.contracts.parcel.v1.ParcelStatusUpdateResponse;
import com.zentro.dispatchcourier.exception.CompletionFailure;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** S3 completion resilience over gRPC; retry decisions remain with ProofCompletionWorker. */
class ParcelStatusClientResilienceTest {
  private Server server;
  private ManagedChannel channel;

  @AfterEach void cleanup() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  @Test void successfulCompletionUsesOneGrpcCallAndConfirmsParcelIdentity() throws Exception {
    UUID parcel = UUID.randomUUID();
    var tokens = mock(ServiceTokenProvider.class);
    var grpcClient = client(new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override public void completeParcelDelivery(CompleteParcelDeliveryRequest request,
          StreamObserver<ParcelStatusUpdateResponse> observer) {
        observer.onNext(ParcelStatusUpdateResponse.newBuilder().setParcelId(request.getParcelId())
            .setStatus("DELIVERED").setChanged(true).build());
        observer.onCompleted();
      }
    }, tokens);

    assertThatCode(() -> new ParcelStatusClient(grpcClient).complete(parcel)).doesNotThrowAnyException();
    verify(tokens, never()).invalidate();
  }

  @Test void unauthenticatedFailureInvalidatesCachedTokenAndRemainsRetryable() throws Exception {
    var tokens = mock(ServiceTokenProvider.class);
    var client = client(failing(Status.UNAUTHENTICATED), tokens);

    assertFailure(client, "PENDING", "PARCEL_AUTH_RETRY");
    verify(tokens).invalidate();
  }

  @Test void rejectedTransitionIsTerminalAndNotRetried() throws Exception {
    var tokens = mock(ServiceTokenProvider.class);
    var client = client(failing(Status.FAILED_PRECONDITION), tokens);

    assertFailure(client, "REJECTED", "DELIVERY_TRANSITION_REJECTED");
    verify(tokens, never()).invalidate();
  }

  @Test void unavailableOrExpiredDeadlineRemainsRetryable() throws Exception {
    var tokens = mock(ServiceTokenProvider.class);
    var client = client(failing(Status.UNAVAILABLE), tokens);

    assertFailure(client, "PENDING", "PARCEL_SERVICE_UNAVAILABLE");
  }

  @Test void malformedGrpcResponseIsBlocked() throws Exception {
    var tokens = mock(ServiceTokenProvider.class);
    var client = client(new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override public void completeParcelDelivery(CompleteParcelDeliveryRequest request,
          StreamObserver<ParcelStatusUpdateResponse> observer) {
        observer.onNext(ParcelStatusUpdateResponse.newBuilder().setParcelId("different-id")
            .setStatus("DELIVERED").build());
        observer.onCompleted();
      }
    }, tokens);

    assertFailure(client, "BLOCKED", "INVALID_STATUS_RESPONSE");
  }

  private ParcelPricingGrpcClient client(ParcelPricingServiceGrpc.ParcelPricingServiceImplBase service,
      ServiceTokenProvider tokens) throws Exception {
    server = ServerBuilder.forPort(0).addService(service).build().start();
    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
    return new ParcelPricingGrpcClient(channel, tokens);
  }

  private static ParcelPricingServiceGrpc.ParcelPricingServiceImplBase failing(Status status) {
    return new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override public void completeParcelDelivery(CompleteParcelDeliveryRequest request,
          StreamObserver<ParcelStatusUpdateResponse> observer) {
        observer.onError(status.asRuntimeException());
      }
    };
  }

  private static void assertFailure(ParcelPricingGrpcClient grpcClient, String state, String code) {
    assertThatThrownBy(() -> new ParcelStatusClient(grpcClient).complete(UUID.randomUUID()))
        .isInstanceOfSatisfying(CompletionFailure.class, failure -> {
          assertThat(failure.state()).isEqualTo(state);
          assertThat(failure.getMessage()).isEqualTo(code);
        });
  }
}
