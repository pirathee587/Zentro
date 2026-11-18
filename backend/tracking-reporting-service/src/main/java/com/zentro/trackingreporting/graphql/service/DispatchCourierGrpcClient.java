package com.zentro.trackingreporting.graphql.service;

import com.zentro.contracts.dispatch.v1.DispatchCourierServiceGrpc;
import com.zentro.contracts.dispatch.v1.GetParcelAssignmentSummaryRequest;
import com.zentro.contracts.dispatch.v1.ParcelAssignmentSummaryResponse;
import com.zentro.security.grpc.BearerServiceGrpcClientInterceptor;
import com.zentro.security.grpc.GrpcServiceTokenProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * gRPC client for calling S3 {@code DispatchCourierService} from S4 using a Keycloak
 * client-credentials SERVICE JWT.
 */
@Component
public class DispatchCourierGrpcClient {
  private final ManagedChannel channel;
  private final DispatchCourierServiceGrpc.DispatchCourierServiceBlockingStub stub;
  private final GrpcServiceTokenProvider tokens;

  @Autowired
  public DispatchCourierGrpcClient(
      @Value("${zentro.service.token-uri:}") String tokenUri,
      @Value("${zentro.service.client-id:zentro-tracking-service}") String clientId,
      @Value("${zentro.service.client-secret:}") String clientSecret,
      @Value("${zentro.dispatch.grpc-host:zentro-dispatch-courier-service}") String host,
      @Value("${zentro.dispatch.grpc-port:9083}") int port) {
    this(new GrpcServiceTokenProvider(tokenUri, clientId, clientSecret), host, port);
  }

  private DispatchCourierGrpcClient(GrpcServiceTokenProvider tokens, String host, int port) {
    this(
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .intercept(new BearerServiceGrpcClientInterceptor(tokens))
            .build(),
        tokens);
  }

  public DispatchCourierGrpcClient(ManagedChannel channel, GrpcServiceTokenProvider tokens) {
    this.channel = channel;
    this.tokens = tokens;
    this.stub = DispatchCourierServiceGrpc.newBlockingStub(channel);
  }

  public Optional<ParcelAssignmentSummaryResponse> getAssignmentSummary(UUID parcelId) {
    if (parcelId == null) {
      return Optional.empty();
    }
    try {
      ParcelAssignmentSummaryResponse resp = stub.withDeadlineAfter(3, TimeUnit.SECONDS)
          .getParcelAssignmentSummary(GetParcelAssignmentSummaryRequest.newBuilder()
              .setParcelId(parcelId.toString())
              .build());
      return resp.getFound() ? Optional.of(resp) : Optional.empty();
    } catch (StatusRuntimeException error) {
      if (error.getStatus().getCode() == Status.Code.UNAUTHENTICATED && tokens != null) {
        tokens.invalidate();
      }
      return Optional.empty();
    }
  }

  @PreDestroy
  public void shutdown() {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdown();
    }
  }
}
