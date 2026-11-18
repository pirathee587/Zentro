package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.contracts.identity.v1.CourierDetails;
import com.zentro.contracts.identity.v1.GetCourierByIdRequest;
import com.zentro.contracts.identity.v1.GetCourierByUserSubjectRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.ListCouriersRequest;
import com.zentro.contracts.identity.v1.ListCouriersResponse;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class IdentityCourierClientTest {
  private static final UUID PROFILE = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

  private Server server;
  private ManagedChannel channel;

  @AfterEach
  void tearDown() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  private IdentityCourierClient clientWith(IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase impl)
      throws Exception {
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return new IdentityCourierClient(new IdentityPartnerGrpcClient(channel, null));
  }

  private static CourierDetails details(boolean active, String status) {
    return CourierDetails.newBuilder()
        .setId(PROFILE.toString())
        .setUserId(USER.toString())
        .setName("Courier")
        .setActive(active)
        .setAccountStatus(status)
        .build();
  }

  private static String jwtWithSub(String sub) {
    String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
    return header + "." + payload + ".sig";
  }

  @Test
  void resolvesSelfProfileWithCourierSubjectAndDistinctIds() throws Exception {
    IdentityCourierClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void getCourierByUserSubject(
          GetCourierByUserSubjectRequest request, StreamObserver<CourierDetails> responseObserver) {
        assertThat(request.getKeycloakSubject()).isEqualTo("kc-sub-1");
        responseObserver.onNext(details(true, "ACTIVE"));
        responseObserver.onCompleted();
      }
    });

    var courier = client.currentCourier(jwtWithSub("kc-sub-1"));
    assertThat(courier.id()).isEqualTo(PROFILE);
    assertThat(courier.userId()).isEqualTo(USER);
  }

  @Test
  void selfProfileFailsClosedForInactiveOrMalformedIdentity() throws Exception {
    IdentityCourierClient inactiveClient = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void getCourierByUserSubject(
          GetCourierByUserSubjectRequest request, StreamObserver<CourierDetails> responseObserver) {
        responseObserver.onNext(details(false, "ACTIVE"));
        responseObserver.onCompleted();
      }
    });
    assertStatus(() -> inactiveClient.currentCourier(jwtWithSub("kc-sub-1")), 403);
    tearDown();

    IdentityCourierClient malformedClient = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void getCourierByUserSubject(
          GetCourierByUserSubjectRequest request, StreamObserver<CourierDetails> responseObserver) {
        responseObserver.onNext(CourierDetails.getDefaultInstance());
        responseObserver.onCompleted();
      }
    });
    assertStatus(() -> malformedClient.currentCourier(jwtWithSub("kc-sub-1")), 503);
  }

  @Test
  void listsOnlyEffectivelyActiveCouriers() throws Exception {
    IdentityCourierClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void listCouriers(
          ListCouriersRequest request, StreamObserver<ListCouriersResponse> responseObserver) {
        assertThat(request.getFilterActive()).isTrue();
        assertThat(request.getActiveOnly()).isTrue();
        responseObserver.onNext(ListCouriersResponse.newBuilder()
            .addAllCouriers(List.of(
                details(true, "ACTIVE"),
                details(false, "ACTIVE"),
                details(true, "SUSPENDED")))
            .build());
        responseObserver.onCompleted();
      }
    });

    assertThat(client.listEligible("dispatcher-token"))
        .extracting(IdentityCourierClient.Courier::id)
        .containsExactly(PROFILE);
  }

  @Test
  void requireEligibleRejectsInactiveOrNotFound() throws Exception {
    IdentityCourierClient inactiveClient = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void getCourierById(
          GetCourierByIdRequest request, StreamObserver<CourierDetails> responseObserver) {
        responseObserver.onNext(details(false, "ACTIVE"));
        responseObserver.onCompleted();
      }
    });
    assertStatus(() -> inactiveClient.requireEligible(PROFILE, "dispatcher-token"), 409);
    tearDown();

    IdentityCourierClient notFoundClient = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void getCourierById(
          GetCourierByIdRequest request, StreamObserver<CourierDetails> responseObserver) {
        responseObserver.onError(Status.NOT_FOUND.withDescription("not found").asRuntimeException());
      }
    });
    assertStatus(() -> notFoundClient.requireEligible(PROFILE, "dispatcher-token"), 404);
  }

  private static void assertStatus(Runnable action, int expectedStatus) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ResponseStatusException.class)
        .extracting(e -> ((ResponseStatusException) e).getStatusCode().value())
        .isEqualTo(expectedStatus);
  }
}
