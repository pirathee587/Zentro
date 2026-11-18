package com.zentro.notificationintegration.assistant.mcp;

import com.zentro.contracts.parcel.v1.CustomerParcelIdRequest;
import com.zentro.contracts.parcel.v1.CustomerParcelPageResponse;
import com.zentro.contracts.parcel.v1.CustomerParcelReferenceRequest;
import com.zentro.contracts.parcel.v1.CustomerParcelViewResponse;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeRequest;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeResponse;
import com.zentro.contracts.parcel.v1.ListCustomerParcelsRequest;
import com.zentro.contracts.parcel.v1.ParcelHistoryResponse;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.security.grpc.BearerServiceGrpcClientInterceptor;
import com.zentro.security.grpc.GrpcServiceTokenProvider;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * gRPC client for calling S2 {@code ParcelPricingService} from S5's customer support MCP tools
 * using a Keycloak client-credentials SERVICE JWT.
 */
@Component("supportParcelPricingGrpcClient")
public class ParcelPricingGrpcClient {
  private final ManagedChannel channel;
  private final ParcelPricingServiceGrpc.ParcelPricingServiceBlockingStub stub;
  private final GrpcServiceTokenProvider tokens;

  @Autowired
  public ParcelPricingGrpcClient(
      @Value("${zentro.service.token-uri:}") String tokenUri,
      @Value("${zentro.service.client-id:zentro-notification-service}") String clientId,
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

  public CustomerParcelViewResponse getCustomerParcelById(String subject, String parcelId) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCustomerParcelById(CustomerParcelIdRequest.newBuilder()
              .setKeycloakSubject(subject)
              .setParcelId(parcelId)
              .build());
    } catch (StatusRuntimeException error) {
      handleAuthError(error);
      throw error;
    }
  }

  public CustomerParcelViewResponse getCustomerParcel(String subject, String reference) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCustomerParcel(CustomerParcelReferenceRequest.newBuilder()
              .setKeycloakSubject(subject)
              .setReference(reference)
              .build());
    } catch (StatusRuntimeException error) {
      handleAuthError(error);
      throw error;
    }
  }

  public ParcelHistoryResponse getCustomerParcelHistory(String subject, String reference) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .getCustomerParcelHistory(CustomerParcelReferenceRequest.newBuilder()
              .setKeycloakSubject(subject)
              .setReference(reference)
              .build());
    } catch (StatusRuntimeException error) {
      handleAuthError(error);
      throw error;
    }
  }

  public CustomerParcelPageResponse listCustomerParcels(String subject, int page, int size) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .listCustomerParcels(ListCustomerParcelsRequest.newBuilder()
              .setKeycloakSubject(subject)
              .setPage(page)
              .setSize(size)
              .build());
    } catch (StatusRuntimeException error) {
      handleAuthError(error);
      throw error;
    }
  }

  public EstimateDeliveryFeeResponse estimateDeliveryFee(
      String pickupCity, String deliveryCity, String weightKg, String destinationType) {
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .estimateDeliveryFee(EstimateDeliveryFeeRequest.newBuilder()
              .setPickupCity(pickupCity)
              .setDeliveryCity(deliveryCity)
              .setWeightKg(weightKg)
              .setDestinationType(destinationType)
              .build());
    } catch (StatusRuntimeException error) {
      handleAuthError(error);
      throw error;
    }
  }

  private void handleAuthError(StatusRuntimeException error) {
    if (error.getStatus().getCode() == Status.Code.UNAUTHENTICATED && tokens != null) {
      tokens.invalidate();
    }
  }

  @PreDestroy
  public void shutdown() {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdown();
    }
  }
}
