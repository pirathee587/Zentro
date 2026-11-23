package com.zentro.trackingreporting.graphql.service;

import com.zentro.contracts.parcel.v1.CustomerDashboardSummaryResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelPageResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelReferenceRequest;
import com.zentro.contracts.parcel.v1.CustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.CustomerSubjectRequest;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeRequest;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeResponse;
import com.zentro.contracts.parcel.v1.ListCustomerParcelsRequest;
import com.zentro.contracts.parcel.v1.OptionalCustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.ParcelHistoryResponse;
import com.zentro.contracts.parcel.v1.ParcelResponse;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.contracts.parcel.v1.PublicTrackingRequest;
import com.zentro.contracts.parcel.v1.PublicTrackingResponse;
import com.zentro.security.grpc.BearerServiceGrpcClientInterceptor;
import com.zentro.security.grpc.GrpcServiceTokenProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * gRPC client for calling S2 {@code ParcelPricingService} from S4 using a Keycloak
 * client-credentials SERVICE JWT.
 */
@Component
public class ParcelPricingGrpcClient {
  private final ManagedChannel channel;
  private final ParcelPricingServiceGrpc.ParcelPricingServiceBlockingStub stub;
  private final GrpcServiceTokenProvider tokens;

  @Autowired
  public ParcelPricingGrpcClient(
      @Value("${zentro.service.token-uri:}") String tokenUri,
      @Value("${zentro.service.client-id:zentro-tracking-service}") String clientId,
      @Value("${zentro.service.client-secret:}") String clientSecret,
      @Value("${zentro.parcel.grpc-host:zentro-parcel-pricing-service}") String host,
      @Value("${zentro.parcel.grpc-port:9082}") int port) {
    this(new GrpcServiceTokenProvider(tokenUri, clientId, clientSecret), host, port);
  }

  private ParcelPricingGrpcClient(GrpcServiceTokenProvider tokens, String host, int port) {
    this(
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .intercept(new BearerServiceGrpcClientInterceptor(tokens))
            .build(),
        tokens);
  }

  public ParcelPricingGrpcClient(ManagedChannel channel, GrpcServiceTokenProvider tokens) {
    this.channel = channel;
    this.tokens = tokens;
    this.stub = ParcelPricingServiceGrpc.newBlockingStub(channel);
  }

  public CustomerDashboardSummaryResponse getSummary(String subject) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCustomerDashboardSummary(CustomerSubjectRequest.newBuilder().setKeycloakSubject(subject).build());
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  public CustomerParcelViewResponse getParcelByReference(String subject, String reference) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCustomerParcel(CustomerParcelReferenceRequest.newBuilder()
              .setKeycloakSubject(subject)
              .setReference(reference)
              .build());
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  public OptionalCustomerParcelViewResponse getLiveParcel(String subject) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCustomerLiveParcel(CustomerSubjectRequest.newBuilder().setKeycloakSubject(subject).build());
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  public CustomerParcelPageResponse listParcels(String subject, int page, int size, String status, String query) {
    try {
      ListCustomerParcelsRequest.Builder req = ListCustomerParcelsRequest.newBuilder()
          .setKeycloakSubject(subject)
          .setPage(page)
          .setSize(size);
      if (status != null) req.setStatus(status);
      if (query != null) req.setQuery(query);
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS).listCustomerParcels(req.build());
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  public PublicTrackingResponse getPublicTracking(String reference) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getPublicTracking(PublicTrackingRequest.newBuilder().setReference(reference).build());
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  /** Minimal authoritative courier view: reference and status only, never customer data. */
  public ParcelResponse getParcel(UUID parcelId) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getParcel(com.zentro.contracts.parcel.v1.GetParcelRequest.newBuilder()
              .setParcelId(parcelId.toString()).build());
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  public ParcelHistoryResponse getHistory(String subject, String reference) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCustomerParcelHistory(CustomerParcelReferenceRequest.newBuilder()
              .setKeycloakSubject(subject)
              .setReference(reference)
              .build());
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  public EstimateDeliveryFeeResponse estimateFee(EstimateDeliveryFeeRequest request) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS).estimateDeliveryFee(request);
    } catch (StatusRuntimeException error) {
      throw mapException(error);
    }
  }

  private RuntimeException mapException(StatusRuntimeException error) {
    Status.Code code = error.getStatus().getCode();
    if (code == Status.Code.UNAUTHENTICATED) {
      if (tokens != null) {
        tokens.invalidate();
      }
      return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Parcel service authentication failed", error);
    }
    if (code == Status.Code.NOT_FOUND) {
      return new ResponseStatusException(HttpStatus.NOT_FOUND, "Parcel not found", error);
    }
    if (code == Status.Code.INVALID_ARGUMENT) {
      return new ResponseStatusException(HttpStatus.BAD_REQUEST, error.getStatus().getDescription(), error);
    }
    if (code == Status.Code.PERMISSION_DENIED) {
      return new ResponseStatusException(HttpStatus.FORBIDDEN, error.getStatus().getDescription(), error);
    }
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Parcel service unavailable", error);
  }

  @PreDestroy
  public void shutdown() {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdown();
    }
  }
}
