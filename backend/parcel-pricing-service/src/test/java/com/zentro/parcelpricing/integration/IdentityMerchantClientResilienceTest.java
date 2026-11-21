package com.zentro.parcelpricing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.MerchantVerificationResponse;
import com.zentro.contracts.identity.v1.VerifyMerchantAccountRequest;
import com.zentro.security.grpc.BearerServiceGrpcClientInterceptor;
import com.zentro.security.grpc.GrpcServiceTokenProvider;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(OutputCaptureExtension.class)
class IdentityMerchantClientResilienceTest {

  private static final String TOKEN = jwtWithSub("merchant-sub-072");

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

  private IdentityMerchantClient clientWith(IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase impl,
      GrpcServiceTokenProvider tokens) throws Exception {
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor().addService(impl).build().start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    return new IdentityMerchantClient(new IdentityPartnerGrpcClient(channel, tokens));
  }

  @Test
  void approvedMerchantPassesWithOneRequest() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    IdentityMerchantClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void verifyMerchantAccount(
          VerifyMerchantAccountRequest req, StreamObserver<MerchantVerificationResponse> obs) {
        requests.incrementAndGet();
        assertThat(req.getKeycloakSubject()).isEqualTo("merchant-sub-072");
        obs.onNext(MerchantVerificationResponse.newBuilder()
            .setKeycloakSubject("merchant-sub-072")
            .setUserId("8a6d1b8e-6f0e-4a57-9a43-0d6b8e2c9f10")
            .setAccountStatus("ACTIVE")
            .setMerchantStatus("APPROVED")
            .setApproved(true)
            .build());
        obs.onCompleted();
      }
    }, null);

    assertThatCode(() -> client.requireApprovedMerchant(TOKEN)).doesNotThrowAnyException();
    assertThat(requests.get()).isEqualTo(1);
  }

  @Test
  void slowIdentityFailsWithinDeadlineWith503() throws Exception {
    IdentityMerchantClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void verifyMerchantAccount(
          VerifyMerchantAccountRequest req, StreamObserver<MerchantVerificationResponse> obs) {
        // Intentionally hang to trigger 5s gRPC deadline.
      }
    }, null);

    long start = System.nanoTime();
    ResponseStatusException error = catchThrowableOfType(
        ResponseStatusException.class, () -> client.requireApprovedMerchant(TOKEN));
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
    GrpcServiceTokenProvider tokens = mock(GrpcServiceTokenProvider.class);
    when(tokens.token()).thenReturn("service-jwt-secret");
    channel = ManagedChannelBuilder.forAddress("127.0.0.1", unusedPort)
        .usePlaintext()
        .intercept(new BearerServiceGrpcClientInterceptor(tokens))
        .build();
    IdentityMerchantClient client = new IdentityMerchantClient(new IdentityPartnerGrpcClient(channel, tokens));

    ResponseStatusException error = catchThrowableOfType(
        ResponseStatusException.class, () -> client.requireApprovedMerchant(TOKEN));

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(output.getAll()).doesNotContain("service-jwt-secret", TOKEN);
  }

  @ParameterizedTest
  @CsvSource({"SUSPENDED,APPROVED", "ACTIVE,PENDING", "DISABLED,APPROVED"})
  void nonApprovedOrSuspendedMerchantBecomes403(String accountStatus, String merchantStatus) throws Exception {
    IdentityMerchantClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void verifyMerchantAccount(
          VerifyMerchantAccountRequest req, StreamObserver<MerchantVerificationResponse> obs) {
        obs.onNext(MerchantVerificationResponse.newBuilder()
            .setAccountStatus(accountStatus)
            .setMerchantStatus(merchantStatus)
            .setApproved(false)
            .build());
        obs.onCompleted();
      }
    }, null);

    ResponseStatusException error = catchThrowableOfType(
        ResponseStatusException.class, () -> client.requireApprovedMerchant(TOKEN));

    assertThat(error.getStatusCode().value()).isEqualTo(403);
  }

  @Test
  void unauthenticatedGrpcInvalidatesServiceTokenAndReturns503(CapturedOutput output) throws Exception {
    GrpcServiceTokenProvider tokens = mock(GrpcServiceTokenProvider.class);
    IdentityMerchantClient client = clientWith(new IdentityPartnerServiceGrpc.IdentityPartnerServiceImplBase() {
      @Override
      public void verifyMerchantAccount(
          VerifyMerchantAccountRequest req, StreamObserver<MerchantVerificationResponse> obs) {
        obs.onError(Status.UNAUTHENTICATED.withDescription("internal identity details").asRuntimeException());
      }
    }, tokens);

    ResponseStatusException error = catchThrowableOfType(
        ResponseStatusException.class, () -> client.requireApprovedMerchant(TOKEN));

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(error.getReason()).doesNotContain("internal identity details");
    assertThat(output.getAll()).doesNotContain(TOKEN);
    verify(tokens).invalidate();
  }
}
