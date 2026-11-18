package com.zentro.dispatchcourier.courier.service;

import com.zentro.contracts.identity.v1.CourierDetails;
import com.zentro.contracts.identity.v1.GetCourierByIdRequest;
import com.zentro.contracts.identity.v1.GetCourierByUserSubjectRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.ListCouriersRequest;
import com.zentro.contracts.identity.v1.ListCouriersResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * gRPC client for calling S1 {@code IdentityPartnerService} with a SERVICE JWT attached via
 * {@link PodGrpcClientInterceptor}.
 */
@Component
public class IdentityPartnerGrpcClient {
  private final ManagedChannel channel;
  private final IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub;
  private final ServiceTokenProvider tokens;

  @Autowired
  public IdentityPartnerGrpcClient(
      ServiceTokenProvider tokens,
      PodGrpcClientInterceptor interceptor,
      @Value("${zentro.identity.grpc-host:zentro-identity-partner-service}") String host,
      @Value("${zentro.identity.grpc-port:9081}") int port) {
    this(
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .intercept(interceptor)
            .build(),
        tokens);
  }

  public IdentityPartnerGrpcClient(ManagedChannel channel, ServiceTokenProvider tokens) {
    this.channel = channel;
    this.tokens = tokens;
    this.stub = IdentityPartnerServiceGrpc.newBlockingStub(channel);
  }

  public IdentityCourierClient.Courier getCourierByUserSubject(String keycloakSubject) {
    if (keycloakSubject == null || keycloakSubject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing courier subject");
    }
    try {
      CourierDetails details = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCourierByUserSubject(GetCourierByUserSubjectRequest.newBuilder()
              .setKeycloakSubject(keycloakSubject.trim())
              .build());
      return toCourier(details);
    } catch (StatusRuntimeException error) {
      throw mapGrpcException(error);
    }
  }

  public List<IdentityCourierClient.Courier> listCouriers(Boolean activeOnly) {
    ListCouriersRequest.Builder req = ListCouriersRequest.newBuilder();
    if (activeOnly != null) {
      req.setFilterActive(true).setActiveOnly(activeOnly);
    }
    try {
      ListCouriersResponse response = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .listCouriers(req.build());
      return response.getCouriersList().stream()
          .map(IdentityPartnerGrpcClient::toCourier)
          .toList();
    } catch (StatusRuntimeException error) {
      throw mapGrpcException(error);
    }
  }

  public IdentityCourierClient.Courier getCourierById(UUID courierId) {
    if (courierId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Courier ID cannot be null");
    }
    try {
      CourierDetails details = stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCourierById(GetCourierByIdRequest.newBuilder()
              .setCourierId(courierId.toString())
              .build());
      return toCourier(details);
    } catch (StatusRuntimeException error) {
      throw mapGrpcException(error);
    }
  }

  private static IdentityCourierClient.Courier toCourier(CourierDetails details) {
    if (details == null || details.getId().isBlank() || details.getUserId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Malformed courier identity response");
    }
    try {
      UUID id = UUID.fromString(details.getId());
      UUID userId = UUID.fromString(details.getUserId());
      return new IdentityCourierClient.Courier(
          id,
          userId,
          details.getEmail(),
          details.getName(),
          details.getPhone(),
          details.getActive(),
          details.getAccountStatus(),
          details.getAssignedZone(),
          details.getVehicleType());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Malformed courier UUID", e);
    }
  }

  private ResponseStatusException mapGrpcException(StatusRuntimeException error) {
    Status.Code code = error.getStatus().getCode();
    if (code == Status.Code.UNAUTHENTICATED) {
      if (tokens != null) {
        tokens.invalidate();
      }
      return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity service authentication failed", error);
    }
    if (code == Status.Code.NOT_FOUND) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, "Courier profile not found", error);
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getStatus().getDescription(), error);
    }
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ResponseStatusException(HttpStatus.FORBIDDEN, error.getStatus().getDescription(), error);
    }
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity service unavailable", error);
  }

  @PreDestroy
  public void shutdown() {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdown();
    }
  }
}
