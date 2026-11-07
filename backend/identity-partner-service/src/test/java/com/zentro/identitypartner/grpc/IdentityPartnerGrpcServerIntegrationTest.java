package com.zentro.identitypartner.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zentro.contracts.identity.v1.CourierProfileResponse;
import com.zentro.contracts.identity.v1.GetCourierProfileRequest;
import com.zentro.contracts.identity.v1.GetMerchantStatusRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.MerchantStatusResponse;
import com.zentro.identitypartner.courier.dto.CourierGrpcResponse;
import com.zentro.identitypartner.courier.service.CourierProfileService;
import com.zentro.identitypartner.grpc.security.GrpcSecurityInterceptor;
import com.zentro.identitypartner.grpc.server.IdentityPartnerGrpcServer;
import com.zentro.identitypartner.grpc.service.IdentityPartnerGrpcService;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class IdentityPartnerGrpcServerIntegrationTest {

  private static final String INTERNAL_SECRET = "test-internal-secret-123";
  private static final UUID COURIER_ID = UUID.randomUUID();

  private CourierProfileService courierProfileService;
  private JwtDecoder jwtDecoder;
  private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  private IdentityPartnerGrpcServer server;
  private ManagedChannel channel;

  @BeforeEach
  void setUp() throws IOException {
    courierProfileService = mock(CourierProfileService.class);
    jwtDecoder = mock(JwtDecoder.class);
    jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);

    IdentityPartnerGrpcService grpcService = new IdentityPartnerGrpcService(courierProfileService, jdbcTemplate);
    GrpcSecurityInterceptor securityInterceptor = new GrpcSecurityInterceptor(INTERNAL_SECRET, jwtDecoder);

    // Port 0 binds to a free ephemeral port on localhost
    server = new IdentityPartnerGrpcServer(0, grpcService, securityInterceptor);
    server.start();

    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort())
        .usePlaintext()
        .build();
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    if (channel != null) {
      channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }
    if (server != null) {
      server.stop();
    }
  }

  @Test
  @DisplayName("Unauthenticated gRPC calls are rejected with UNAUTHENTICATED")
  void unauthenticatedCallFails() {
    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel);

    GetCourierProfileRequest request = GetCourierProfileRequest.newBuilder()
        .setCourierId(COURIER_ID.toString())
        .build();

    assertThatThrownBy(() -> stub.getCourierProfile(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(ex -> {
          StatusRuntimeException sre = (StatusRuntimeException) ex;
          assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        });
  }

  @Test
  @DisplayName("Invalid internal token is rejected with UNAUTHENTICATED")
  void invalidInternalTokenFails() {
    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, "wrong-secret");

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetCourierProfileRequest request = GetCourierProfileRequest.newBuilder()
        .setCourierId(COURIER_ID.toString())
        .build();

    assertThatThrownBy(() -> stub.getCourierProfile(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(ex -> {
          StatusRuntimeException sre = (StatusRuntimeException) ex;
          assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED);
        });
  }

  @Test
  @DisplayName("Valid internal token succeeds and returns active courier status")
  void validInternalTokenReturnsActiveCourier() {
    when(courierProfileService.getCourierProfileForGrpc(COURIER_ID.toString()))
        .thenReturn(new CourierGrpcResponse(COURIER_ID.toString(), true));

    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, INTERNAL_SECRET);
    headers.put(GrpcSecurityInterceptor.INTERNAL_SERVICE_METADATA_KEY, "dispatch-courier-service");

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetCourierProfileRequest request = GetCourierProfileRequest.newBuilder()
        .setCourierId(COURIER_ID.toString())
        .build();

    CourierProfileResponse response = stub.getCourierProfile(request);

    assertThat(response).isNotNull();
    assertThat(response.getCourierId()).isEqualTo(COURIER_ID.toString());
    assertThat(response.getActive()).isTrue();
  }

  @Test
  @DisplayName("Valid internal token returns inactive for non-existent or inactive courier")
  void validInternalTokenReturnsInactiveCourier() {
    UUID missingId = UUID.randomUUID();
    when(courierProfileService.getCourierProfileForGrpc(missingId.toString()))
        .thenReturn(new CourierGrpcResponse(missingId.toString(), false));

    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, INTERNAL_SECRET);

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetCourierProfileRequest request = GetCourierProfileRequest.newBuilder()
        .setCourierId(missingId.toString())
        .build();

    CourierProfileResponse response = stub.getCourierProfile(request);

    assertThat(response).isNotNull();
    assertThat(response.getCourierId()).isEqualTo(missingId.toString());
    assertThat(response.getActive()).isFalse();
  }

  @Test
  @DisplayName("Valid Keycloak JWT with SERVICE role succeeds over gRPC")
  void validJwtBearerTokenSucceeds() {
    String token = "valid.jwt.token";
    Jwt jwt = mock(Jwt.class);
    when(jwt.getSubject()).thenReturn("s3-dispatch-service");
    when(jwt.getClaim("realm_access")).thenReturn(Map.of("roles", List.of("SERVICE")));
    when(jwtDecoder.decode(token)).thenReturn(jwt);

    when(courierProfileService.getCourierProfileForGrpc(COURIER_ID.toString()))
        .thenReturn(new CourierGrpcResponse(COURIER_ID.toString(), true));

    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + token);

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetCourierProfileRequest request = GetCourierProfileRequest.newBuilder()
        .setCourierId(COURIER_ID.toString())
        .build();

    CourierProfileResponse response = stub.getCourierProfile(request);

    assertThat(response).isNotNull();
    assertThat(response.getActive()).isTrue();
  }

  @Test
  @DisplayName("JWT with unauthorized role is rejected with PERMISSION_DENIED")
  void unauthorizedRoleJwtFails() {
    String token = "customer.jwt.token";
    Jwt jwt = mock(Jwt.class);
    when(jwt.getSubject()).thenReturn("random-customer");
    when(jwt.getClaim("realm_access")).thenReturn(Map.of("roles", List.of("CUSTOMER")));
    when(jwtDecoder.decode(token)).thenReturn(jwt);

    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer " + token);

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetCourierProfileRequest request = GetCourierProfileRequest.newBuilder()
        .setCourierId(COURIER_ID.toString())
        .build();

    assertThatThrownBy(() -> stub.getCourierProfile(request))
        .isInstanceOf(StatusRuntimeException.class)
        .satisfies(ex -> {
          StatusRuntimeException sre = (StatusRuntimeException) ex;
          assertThat(sre.getStatus().getCode()).isEqualTo(Status.Code.PERMISSION_DENIED);
        });
  }

  @Test
  @DisplayName("Merchant status RPC returns APPROVED for an approved merchant")
  void getMerchantStatusReturnsApproved() {
    when(jdbcTemplate.queryForObject(
        anyString(),
        eq(String.class),
        eq("merchant-approved-subject")))
        .thenReturn("APPROVED");

    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, INTERNAL_SECRET);

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetMerchantStatusRequest request = GetMerchantStatusRequest.newBuilder()
        .setMerchantId("merchant-approved-subject")
        .build();

    MerchantStatusResponse response = stub.getMerchantStatus(request);

    assertThat(response).isNotNull();
    assertThat(response.getMerchantId()).isEqualTo("merchant-approved-subject");
    assertThat(response.getStatus()).isEqualTo("APPROVED");
  }

  @Test
  @DisplayName("Merchant status RPC returns SUSPENDED for a suspended merchant")
  void getMerchantStatusReturnsSuspended() {
    when(jdbcTemplate.queryForObject(
        anyString(),
        eq(String.class),
        eq("merchant-suspended-subject")))
        .thenReturn("SUSPENDED");

    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, INTERNAL_SECRET);

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetMerchantStatusRequest request = GetMerchantStatusRequest.newBuilder()
        .setMerchantId("merchant-suspended-subject")
        .build();

    MerchantStatusResponse response = stub.getMerchantStatus(request);

    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("SUSPENDED");
  }

  @Test
  @DisplayName("Merchant status RPC returns NOT_FOUND when no application exists")
  void getMerchantStatusReturnsNotFoundForUnknownMerchant() {
    when(jdbcTemplate.queryForObject(
        anyString(),
        eq(String.class),
        eq("unknown-subject")))
        .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, INTERNAL_SECRET);

    IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub =
        IdentityPartnerServiceGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

    GetMerchantStatusRequest request = GetMerchantStatusRequest.newBuilder()
        .setMerchantId("unknown-subject")
        .build();

    MerchantStatusResponse response = stub.getMerchantStatus(request);

    assertThat(response).isNotNull();
    assertThat(response.getStatus()).isEqualTo("NOT_FOUND");
  }
}
