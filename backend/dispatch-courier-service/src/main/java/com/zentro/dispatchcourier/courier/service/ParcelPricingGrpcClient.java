package com.zentro.dispatchcourier.courier.service;

import com.zentro.contracts.parcel.v1.CompleteParcelDeliveryRequest;
import com.zentro.contracts.parcel.v1.GetParcelRequest;
import com.zentro.contracts.parcel.v1.ParcelResponse;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.contracts.parcel.v1.ParcelStatusUpdateResponse;
import com.zentro.contracts.parcel.v1.UpdateCourierParcelStatusRequest;
import com.zentro.dispatchcourier.exception.CompletionFailure;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * gRPC client for calling S2 ParcelPricingService with a SERVICE JWT attached via {@link PodGrpcClientInterceptor}.
 */
@Component
public class ParcelPricingGrpcClient {
  private final ManagedChannel channel;
  private final ParcelPricingServiceGrpc.ParcelPricingServiceBlockingStub stub;
  private final ServiceTokenProvider tokens;

  @Autowired
  public ParcelPricingGrpcClient(
      ServiceTokenProvider tokens,
      PodGrpcClientInterceptor interceptor,
      @Value("${zentro.pod.parcel-grpc-host:zentro-parcel-pricing-service}") String host,
      @Value("${zentro.pod.parcel-grpc-port:9082}") int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().intercept(interceptor).build(), tokens);
  }

  public ParcelPricingGrpcClient(ManagedChannel channel, ServiceTokenProvider tokens) {
    this.channel = channel;
    this.tokens = tokens;
    this.stub = ParcelPricingServiceGrpc.newBlockingStub(channel);
  }

  public void completeDelivery(UUID parcelId) {
    try {
      ParcelStatusUpdateResponse response = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .completeParcelDelivery(CompleteParcelDeliveryRequest.newBuilder()
              .setParcelId(parcelId.toString())
              .build());
      if (!parcelId.toString().equals(response.getParcelId()) || !"DELIVERED".equals(response.getStatus())) {
        throw new CompletionFailure("BLOCKED", "INVALID_STATUS_RESPONSE");
      }
    } catch (StatusRuntimeException error) {
      Status.Code code = error.getStatus().getCode();
      if (code == Status.Code.UNAUTHENTICATED) {
        tokens.invalidate();
        throw new CompletionFailure("PENDING", "PARCEL_AUTH_RETRY");
      }
      if (code == Status.Code.FAILED_PRECONDITION) {
        throw new CompletionFailure("REJECTED", "DELIVERY_TRANSITION_REJECTED");
      }
      if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
        throw new CompletionFailure("PENDING", "PARCEL_SERVICE_UNAVAILABLE");
      }
      throw new CompletionFailure("BLOCKED", "PARCEL_REQUEST_REJECTED");
    }
  }

  public ParcelResponse getParcel(UUID parcelId) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS).getParcel(
          GetParcelRequest.newBuilder().setParcelId(parcelId.toString()).build());
    } catch (StatusRuntimeException error) {
      if (error.getStatus().getCode() == Status.Code.UNAUTHENTICATED) tokens.invalidate();
      throw error;
    }
  }

  public Map<?, ?> updateCourierStatus(UUID parcelId, String targetStatus, String reason, String note, String courierSubject) {
    var req = UpdateCourierParcelStatusRequest.newBuilder()
        .setParcelId(parcelId.toString())
        .setStatus(targetStatus == null ? "" : targetStatus)
        .setReason(reason == null ? "" : reason)
        .setNote(note == null ? "" : note)
        .setCourierSubject(courierSubject == null ? "" : courierSubject)
        .build();
    try {
      ParcelStatusUpdateResponse res = stub.withDeadlineAfter(5, TimeUnit.SECONDS).updateCourierParcelStatus(req);
      return Map.of(
          "parcelId", res.getParcelId(),
          "trackingRef", res.getTrackingRef(),
          "previousStatus", res.getPreviousStatus(),
          "status", res.getStatus(),
          "changed", res.getChanged());
    } catch (StatusRuntimeException error) {
      if (error.getStatus().getCode() == Status.Code.UNAUTHENTICATED) {
        tokens.invalidate();
      }
      throw error;
    }
  }

  @PreDestroy
  public void shutdown() {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdown();
    }
  }
}
