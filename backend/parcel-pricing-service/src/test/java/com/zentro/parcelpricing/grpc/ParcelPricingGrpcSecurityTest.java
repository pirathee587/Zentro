package com.zentro.parcelpricing.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zentro.contracts.parcel.v1.CompleteParcelDeliveryRequest;
import com.zentro.contracts.parcel.v1.GetParcelRequest;
import com.zentro.contracts.parcel.v1.ParcelPricingServiceGrpc;
import com.zentro.contracts.parcel.v1.ParcelStatusUpdateResponse;
import com.zentro.parcelpricing.grpc.security.PodGrpcSecurityInterceptor;
import com.zentro.parcelpricing.grpc.server.ParcelPricingGrpcServer;
import com.zentro.parcelpricing.grpc.service.ParcelPricingGrpcService;
import com.zentro.parcelpricing.parcel.dto.ParcelStatusResponse;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import com.zentro.parcelpricing.parcel.repository.ParcelBookingRepository;
import com.zentro.parcelpricing.parcel.service.ParcelStatusService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class ParcelPricingGrpcSecurityTest {
  private ParcelBookingRepository bookingRepository;
  private ParcelStatusService statusService;
  private JwtDecoder jwtDecoder;
  private ParcelPricingGrpcServer server;
  private ManagedChannel channel;

  @BeforeEach
  void setUp() throws Exception {
    bookingRepository = mock(ParcelBookingRepository.class);
    statusService = mock(ParcelStatusService.class);
    jwtDecoder = mock(JwtDecoder.class);
    var grpcService = new ParcelPricingGrpcService(bookingRepository, statusService);
    var interceptor = new PodGrpcSecurityInterceptor(jwtDecoder);
    server = new ParcelPricingGrpcServer(0, grpcService, interceptor);
    server.start();
    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
  }

  @AfterEach
  void tearDown() {
    if (channel != null) channel.shutdownNow();
    if (server != null) server.stop();
  }

  @Test
  void missingBearerTokenIsRejectedWithUnauthenticated() {
    var stub = ParcelPricingServiceGrpc.newBlockingStub(channel);
    assertThatThrownBy(() -> stub.completeParcelDelivery(
        CompleteParcelDeliveryRequest.newBuilder().setParcelId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  void invalidJwtIsRejectedWithUnauthenticated() {
    when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("expired"));
    var stub = stubWithToken("bad-token");
    assertThatThrownBy(() -> stub.completeParcelDelivery(
        CompleteParcelDeliveryRequest.newBuilder().setParcelId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.UNAUTHENTICATED);
  }

  @Test
  void courierTokenWithoutServiceRoleIsRejectedWithPermissionDenied() {
    when(jwtDecoder.decode("courier-jwt")).thenReturn(jwt("courier-1", List.of("COURIER"), "pod:update"));
    var stub = stubWithToken("courier-jwt");
    assertThatThrownBy(() -> stub.completeParcelDelivery(
        CompleteParcelDeliveryRequest.newBuilder().setParcelId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  void serviceTokenWithoutRequiredPermissionIsRejectedWithPermissionDenied() {
    when(jwtDecoder.decode("service-readonly")).thenReturn(jwt("zentro-dispatch-service", List.of("SERVICE"), "pod:read"));
    var stub = stubWithToken("service-readonly");
    assertThatThrownBy(() -> stub.completeParcelDelivery(
        CompleteParcelDeliveryRequest.newBuilder().setParcelId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  void trackingServiceCannotUsePodCompletionRpcEvenWithPodPermissionClaim() {
    when(jwtDecoder.decode("wrong-service"))
        .thenReturn(jwt("zentro-tracking-service", List.of("SERVICE"), "pod:read pod:update"));
    var stub = stubWithToken("wrong-service");
    assertThatThrownBy(() -> stub.completeParcelDelivery(
        CompleteParcelDeliveryRequest.newBuilder().setParcelId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  void dispatchServiceWithoutPodPermissionCannotReadPodRpc() {
    when(jwtDecoder.decode("dispatch-no-permission"))
        .thenReturn(jwt("zentro-dispatch-service", List.of("SERVICE"), ""));
    var stub = stubWithToken("dispatch-no-permission");
    assertThatThrownBy(() -> stub.getParcel(GetParcelRequest.newBuilder().setParcelId(UUID.randomUUID().toString()).build()))
        .isInstanceOf(StatusRuntimeException.class)
        .extracting(e -> ((StatusRuntimeException) e).getStatus().getCode())
        .isEqualTo(Status.Code.PERMISSION_DENIED);
  }

  @Test
  void validServiceJwtWithPodUpdateExecutesProtectedGrpcOperation() {
    UUID parcelId = UUID.randomUUID();
    when(jwtDecoder.decode("valid-service-jwt"))
        .thenReturn(jwt("zentro-dispatch-service", List.of("SERVICE"), "pod:read pod:update"));
    when(statusService.updateStatus(parcelId, ParcelStatus.DELIVERED, null, null, "zentro-dispatch-service", "SERVICE"))
        .thenReturn(new ParcelStatusResponse(
            parcelId, "ZT-GRPC1234", ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, true, OffsetDateTime.now()));

    var stub = stubWithToken("valid-service-jwt");
    ParcelStatusUpdateResponse response = stub.completeParcelDelivery(
        CompleteParcelDeliveryRequest.newBuilder().setParcelId(parcelId.toString()).build());

    assertThat(response.getParcelId()).isEqualTo(parcelId.toString());
    assertThat(response.getStatus()).isEqualTo("DELIVERED");
    assertThat(response.getChanged()).isTrue();
  }

  private ParcelPricingServiceGrpc.ParcelPricingServiceBlockingStub stubWithToken(String token) {
    Metadata metadata = new Metadata();
    metadata.put(PodGrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + token);
    return ParcelPricingServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
  }

  private static Jwt jwt(String subject, List<String> roles, String scope) {
    return Jwt.withTokenValue("tok")
        .header("alg", "RS256")
        .subject(subject)
        .claim("azp", subject)
        .claim("realm_access", Map.of("roles", roles))
        .claim("scope", scope)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(300))
        .build();
  }
}
