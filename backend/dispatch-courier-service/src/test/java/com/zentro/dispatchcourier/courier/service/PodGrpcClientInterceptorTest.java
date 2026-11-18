package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zentro.contracts.parcel.v1.CompleteParcelDeliveryRequest;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.contracts.parcel.v1.ParcelStatusUpdateResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PodGrpcClientInterceptorTest {
  private Server server;
  private ManagedChannel channel;

  @AfterEach
  void tearDown() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  @Test
  void attachesBearerServiceJwtToOutgoingGrpcMetadata() throws Exception {
    AtomicReference<String> capturedAuth = new AtomicReference<>();
    var impl = new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override
      public void completeParcelDelivery(CompleteParcelDeliveryRequest request,
          StreamObserver<ParcelStatusUpdateResponse> responseObserver) {
        responseObserver.onNext(ParcelStatusUpdateResponse.newBuilder()
            .setParcelId(request.getParcelId())
            .setStatus("DELIVERED")
            .setChanged(true)
            .build());
        responseObserver.onCompleted();
      }
    };
    server = ServerBuilder.forPort(0)
        .addService(impl)
        .intercept(new ServerInterceptor() {
          @Override
          public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
              ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            capturedAuth.set(headers.get(PodGrpcClientInterceptor.AUTHORIZATION_METADATA_KEY));
            return next.startCall(call, headers);
          }
        })
        .build()
        .start();

    ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
    when(tokens.token()).thenReturn("service-jwt-value");
    PodGrpcClientInterceptor interceptor = new PodGrpcClientInterceptor(tokens);
    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
        .usePlaintext()
        .intercept(interceptor)
        .build();

    ParcelPricingGrpcClient client = new ParcelPricingGrpcClient(channel, tokens);
    client.completeDelivery(UUID.randomUUID());

    assertThat(capturedAuth.get()).isEqualTo("Bearer service-jwt-value");
  }
}
