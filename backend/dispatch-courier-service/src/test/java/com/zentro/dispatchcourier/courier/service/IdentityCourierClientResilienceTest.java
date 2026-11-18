package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.contracts.identity.v1.CourierDetails;
import com.zentro.contracts.identity.v1.GetCourierByIdRequest;
import com.zentro.contracts.identity.v1.GetCourierByUserSubjectRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.ListCouriersRequest;
import com.zentro.contracts.identity.v1.ListCouriersResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(OutputCaptureExtension.class)
class IdentityCourierClientResilienceTest {

  private static final UUID PROFILE_ID = UUID.fromString("3f0c2a6e-1d4b-4e8a-9b7c-5a2d1e0f9c11");
  private static final UUID USER_ID = UUID.fromString("7b1e4d2c-9a3f-4c6b-8e5d-2f0a1b3c4d55");
  private static final String TOKEN = jwtWithSub("courier-sub-072");

  private Server server;
  private ManagedChannel channel;

  @AfterEach
  void stopStub() {
    if (channel != null) {
      channel.shutdownNow();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  private static String jwtWithSub(String sub) {
    String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
    String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"sub\":\"" + sub + "\"}").getBytes(StandardCharsets.UTF_8));
    return header + "." + payload + ".sig";
  }

  private static CourierDetails sampleCourier() {
    return CourierDetails.newBuilder()
        .setId(PROFILE_ID.toString())
        .setUserId(USER_ID.toString())
        .setName("Kamal Silva")
        .setActive(true)
        .setAccountStatus("ACTIVE")
        .build();
  }

  private IdentityCourierClient clientWith(IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase impl,
      ServiceTokenProvider tokens) throws Exception {
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return new IdentityCourierClient(new IdentityPartnerGrpcClient(channel, tokens));
  }

  static Stream<Arguments> lookups() {
    return Stream.of(
        Arguments.of("currentCourier", (Function<IdentityCourierClient, Object>) c -> c.currentCourier(TOKEN)),
        Arguments.of("listEligible", (Function<IdentityCourierClient, Object>) c -> c.listEligible(TOKEN)),
        Arguments.of("listAll", (Function<IdentityCourierClient, Object>) c -> c.listAll(TOKEN)),
        Arguments.of("requireEligible", (Function<IdentityCourierClient, Object>) c -> c.requireEligible(PROFILE_ID, TOKEN)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lookups")
  void healthyIdentityAnswersWithOneRequest(String name, Function<IdentityCourierClient, Object> call) throws Exception {
    AtomicInteger requests = new AtomicInteger();
    IdentityCourierClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void getCourierByUserSubject(GetCourierByUserSubjectRequest req, StreamObserver<CourierDetails> obs) {
        requests.incrementAndGet();
        obs.onNext(sampleCourier());
        obs.onCompleted();
      }

      @Override
      public void listCouriers(ListCouriersRequest req, StreamObserver<ListCouriersResponse> obs) {
        requests.incrementAndGet();
        obs.onNext(ListCouriersResponse.newBuilder().addCouriers(sampleCourier()).build());
        obs.onCompleted();
      }

      @Override
      public void getCourierById(GetCourierByIdRequest req, StreamObserver<CourierDetails> obs) {
        requests.incrementAndGet();
        obs.onNext(sampleCourier());
        obs.onCompleted();
      }
    }, null);

    assertThat(call.apply(client)).isNotNull();
    assertThat(requests.get()).isEqualTo(1);
  }

  @Test
  void slowIdentityFailsWithinDeadlineWith503() throws Exception {
    IdentityCourierClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void listCouriers(ListCouriersRequest req, StreamObserver<ListCouriersResponse> obs) {
        // Intentionally do not complete so the 5s gRPC deadline triggers.
      }
    }, null);

    long start = System.nanoTime();
    ResponseStatusException error = catchThrowableOfType(ResponseStatusException.class, () -> client.listEligible(TOKEN));
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(elapsedMs).isBetween(4500L, 7000L);
  }

  @Test
  void refusedConnectionFailsFastWith503(CapturedOutput output) throws Exception {
    int unusedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      unusedPort = socket.getLocalPort();
    }
    ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
    when(tokens.token()).thenReturn("service-jwt-secret");
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", unusedPort)
        .usePlaintext()
        .intercept(new PodGrpcClientInterceptor(tokens))
        .build();
    IdentityCourierClient client = new IdentityCourierClient(new IdentityPartnerGrpcClient(channel, tokens));

    ResponseStatusException error = catchThrowableOfType(ResponseStatusException.class, () -> client.listEligible(TOKEN));

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(output.getOut() + output.getErr()).doesNotContain("service-jwt-secret");
  }

  @Test
  void unauthenticatedGrpcInvalidatesServiceTokenAndReturns503() throws Exception {
    ServiceTokenProvider tokens = mock(ServiceTokenProvider.class);
    IdentityCourierClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void listCouriers(ListCouriersRequest req, StreamObserver<ListCouriersResponse> obs) {
        obs.onError(Status.UNAUTHENTICATED.withDescription("internal token detail").asRuntimeException());
      }
    }, tokens);

    ResponseStatusException error = catchThrowableOfType(ResponseStatusException.class, () -> client.listEligible(TOKEN));

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(error.getReason()).doesNotContain("internal token detail");
    verify(tokens).invalidate();
  }
}
