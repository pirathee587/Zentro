package com.zentro.trackingreporting.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeRequest;
import com.zentro.contracts.parcel.v1.EstimateDeliveryFeeResponse;
import com.zentro.contracts.parcel.v1.FeeBreakdownProto;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.trackingreporting.graphql.service.DashboardPricingClient;
import com.zentro.trackingreporting.graphql.service.ParcelPricingGrpcClient;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class DashboardPricingClientTest {
  private Server server;
  private ManagedChannel channel;

  @AfterEach
  void tearDown() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.shutdownNow();
  }

  private DashboardPricingClient clientWith(ParcelPricingServiceGrpc.ParcelPricingServiceImplBase impl)
      throws Exception {
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return new DashboardPricingClient(new ParcelPricingGrpcClient(channel, null));
  }

  @Test
  void forwardsQuotationOverGrpcAndUsesActualResponse() throws Exception {
    var client = clientWith(new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override
      public void estimateDeliveryFee(
          EstimateDeliveryFeeRequest req, StreamObserver<EstimateDeliveryFeeResponse> obs) {
        assertThat(req.getWeightKg()).isEqualTo("2.5");
        assertThat(req.getPickupCity()).isEmpty();
        obs.onNext(EstimateDeliveryFeeResponse.newBuilder()
            .setFee("925.5")
            .setCurrency("LKR")
            .setFormattedFee("LKR 925.50")
            .setEstimatedDays(3)
            .setBreakdown(FeeBreakdownProto.newBuilder()
                .setBaseFee("500")
                .setWeightFee("425.5")
                .build())
            .build());
        obs.onCompleted();
      }
    });

    var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "RS256").subject("alice").build();
    assertThat(client.estimate(jwt, null, null, 2.5, "OTHER_DISTRICT", "Medium").fee())
        .isEqualByComparingTo("925.5");
    assertThatThrownBy(() -> client.estimate(jwt, "Colombo", "Kandy", Double.NaN, "OTHER_DISTRICT", null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void failuresAreSanitizedAndNeverSubstitutePrices() throws Exception {
    var client = clientWith(new ParcelPricingServiceGrpc.ParcelPricingServiceImplBase() {
      @Override
      public void estimateDeliveryFee(
          EstimateDeliveryFeeRequest req, StreamObserver<EstimateDeliveryFeeResponse> obs) {
        obs.onError(Status.INTERNAL.withDescription("private upstream body").asRuntimeException());
      }
    });

    var jwt = Jwt.withTokenValue("synthetic-token").header("alg", "RS256").subject("alice").build();
    assertThatThrownBy(() -> client.estimate(jwt, "Colombo", "Kandy", 2, "OTHER_DISTRICT", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("temporarily unavailable")
        .hasMessageNotContaining("private upstream body");
  }
}
