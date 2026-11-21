package com.zentro.parcelpricing.integration;

import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.contracts.identity.v1.MerchantVerificationResponse;
import com.zentro.contracts.identity.v1.VerifyMerchantAccountRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * gRPC client for calling S1 {@code IdentityPartnerService.VerifyMerchantAccount} using a
 * Keycloak client-credentials SERVICE JWT.
 */
@Component
public class IdentityPartnerGrpcClient {
  private final ManagedChannel channel;
  private final IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub;
  private final GrpcServiceTokenProvider tokens;

  @Autowired
  public IdentityPartnerGrpcClient(
      @Value("${zentro.service.token-uri:}") String tokenUri,
      @Value("${zentro.service.client-id:zentro-parcel-service}") String clientId,
      @Value("${zentro.service.client-secret:}") String clientSecret,
      @Value("${zentro.identity.grpc-host:zentro-identity-partner-service}") String host,
      @Value("${zentro.identity.grpc-port:9081}") int port) {
    this(new GrpcServiceTokenProvider(tokenUri, clientId, clientSecret), host, port);
  }

  private IdentityPartnerGrpcClient(GrpcServiceTokenProvider tokens, String host, int port) {
    this(
        ManagedChannelBuilder.forAddress(host, port)
            .usePlaintext()
            .intercept(new BearerServiceGrpcClientInterceptor(tokens))
            .build(),
        tokens);
  }

  public IdentityPartnerGrpcClient(ManagedChannel channel, GrpcServiceTokenProvider tokens) {
    this.channel = channel;
    this.tokens = tokens;
    this.stub = IdentityPartnerServiceGrpc.newBlockingStub(channel);
  }

  public MerchantVerificationResponse verifyMerchantAccount(String keycloakSubject) {
    if (keycloakSubject == null || keycloakSubject.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing merchant subject");
    }
    try {
      return stub.withDeadlineAfter(5, TimeUnit.SECONDS)
          .verifyMerchantAccount(VerifyMerchantAccountRequest.newBuilder()
              .setKeycloakSubject(keycloakSubject.trim())
              .build());
    } catch (StatusRuntimeException error) {
      Status.Code code = error.getStatus().getCode();
      if (code == Status.Code.UNAUTHENTICATED) {
        if (tokens != null) {
          tokens.invalidate();
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity service authentication failed", error);
      }
      if (code == Status.Code.NOT_FOUND || code == Status.Code.PERMISSION_DENIED) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Merchant account is not approved", error);
      }
      if (code == Status.Code.INVALID_ARGUMENT) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid merchant verification request", error);
      }
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Identity service unavailable", error);
    }
  }

  @PreDestroy
  public void shutdown() {
    if (channel != null && !channel.isShutdown()) {
      channel.shutdown();
    }
  }
}
