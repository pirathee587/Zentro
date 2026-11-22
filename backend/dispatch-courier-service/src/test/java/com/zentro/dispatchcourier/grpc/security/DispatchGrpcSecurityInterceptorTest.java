package com.zentro.dispatchcourier.grpc.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zentro.contracts.dispatch.v1.DispatchCourierServiceGrpc;
import com.zentro.contracts.dispatch.v1.GetParcelAssignmentSummaryRequest;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.grpc.server.DispatchCourierGrpcServer;
import com.zentro.dispatchcourier.grpc.service.DispatchCourierGrpcService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class DispatchGrpcSecurityInterceptorTest {
  private JwtDecoder decoder;
  private DispatchCourierGrpcServer server;
  private ManagedChannel channel;

  @BeforeEach
  void start() throws Exception {
    decoder = mock(JwtDecoder.class);
    AssignmentRepository assignments = mock(AssignmentRepository.class);
    when(assignments.find(org.mockito.ArgumentMatchers.any())).thenReturn(Optional.empty());
    server = new DispatchCourierGrpcServer(0, new DispatchCourierGrpcService(assignments),
        new DispatchGrpcSecurityInterceptor(decoder));
    server.start();
    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
  }

  @AfterEach
  void stop() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.stop();
  }

  @Test
  void missingTokenIsRejected() {
    assertStatus(DispatchCourierServiceGrpc.newBlockingStub(channel), Status.Code.UNAUTHENTICATED);
  }

  @Test
  void wrongServiceClientIsRejectedEvenWithServiceRole() {
    when(decoder.decode("dispatch-token")).thenReturn(jwt("zentro-dispatch-service"));
    assertStatus(stub("dispatch-token"), Status.Code.PERMISSION_DENIED);
  }

  @Test
  void trackingServiceCanReadAssignmentSummary() {
    when(decoder.decode("tracking-token")).thenReturn(jwt("zentro-tracking-service"));
    var response = stub("tracking-token").getParcelAssignmentSummary(GetParcelAssignmentSummaryRequest.newBuilder()
        .setParcelId(UUID.randomUUID().toString()).build());
    assertThat(response.getFound()).isFalse();
  }

  @Test
  void invalidOrExpiredJwtIsRejected() {
    when(decoder.decode("invalid-token")).thenThrow(new JwtException("invalid signature or expired"));
    assertStatus(stub("invalid-token"), Status.Code.UNAUTHENTICATED);
  }

  @Test void notificationRecipientRpcIsRestrictedToS5() {
    when(decoder.decode("notification-token")).thenReturn(jwt("zentro-notification-service"));
    var request=GetParcelAssignmentSummaryRequest.newBuilder().setParcelId(UUID.randomUUID().toString()).build();
    assertThat(stub("notification-token").resolveNotificationCourier(request).getCourierProfileId()).isEmpty();
    when(decoder.decode("tracking-token")).thenReturn(jwt("zentro-tracking-service"));
    assertThatThrownBy(()->stub("tracking-token").resolveNotificationCourier(request))
        .isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED));
    assertStatus(stub("notification-token"),Status.Code.PERMISSION_DENIED);
  }

  private DispatchCourierServiceGrpc.DispatchCourierServiceBlockingStub stub(String token) {
    Metadata headers = new Metadata();
    headers.put(DispatchGrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + token);
    return DispatchCourierServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private static void assertStatus(DispatchCourierServiceGrpc.DispatchCourierServiceBlockingStub stub,
      Status.Code expected) {
    assertThatThrownBy(() -> stub.getParcelAssignmentSummary(GetParcelAssignmentSummaryRequest.newBuilder()
        .setParcelId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(error -> ((StatusRuntimeException) error).getStatus().getCode())
        .isEqualTo(expected);
  }

  private static Jwt jwt(String clientId) {
    return Jwt.withTokenValue("test")
        .header("alg", "RS256")
        .subject("service-account-" + clientId)
        .claim("azp", clientId)
        .claim("realm_access", Map.of("roles", List.of("SERVICE")))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }
}
