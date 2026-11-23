package com.zentro.notificationintegration.inbox;

import com.zentro.contracts.dispatch.v1.DispatchCourierServiceGrpc;
import com.zentro.contracts.dispatch.v1.GetParcelAssignmentSummaryRequest;
import com.zentro.security.grpc.BearerServiceGrpcClientInterceptor;
import com.zentro.security.grpc.GrpcServiceTokenProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NotificationAssignmentClient {
  private final ManagedChannel channel;
  public NotificationAssignmentClient(@Value("${zentro.service.token-uri:}") String uri,
      @Value("${zentro.service.client-id:zentro-notification-service}") String id,
      @Value("${zentro.service.client-secret:}") String secret,
      @Value("${zentro.dispatch.grpc-host:zentro-dispatch-courier-service}") String host,
      @Value("${zentro.dispatch.grpc-port:9083}") int port) {
    channel=ManagedChannelBuilder.forAddress(host,port).usePlaintext()
        .intercept(new BearerServiceGrpcClientInterceptor(new GrpcServiceTokenProvider(uri,id,secret))).build();
  }
  public String courier(UUID parcel) {
    return DispatchCourierServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5,TimeUnit.SECONDS)
        .resolveNotificationCourier(GetParcelAssignmentSummaryRequest.newBuilder().setParcelId(parcel.toString()).build()).getCourierProfileId();
  }
  @PreDestroy public void close() { channel.shutdownNow(); }
}
