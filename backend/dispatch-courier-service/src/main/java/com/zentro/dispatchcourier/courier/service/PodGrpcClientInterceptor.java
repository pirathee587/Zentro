package com.zentro.dispatchcourier.courier.service;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.stereotype.Component;

/**
 * Attaches the short-lived OAuth2 SERVICE JWT from {@link ServiceTokenProvider}
 * to outgoing gRPC metadata without logging token values.
 */
@Component
public class PodGrpcClientInterceptor implements ClientInterceptor {
  public static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final ServiceTokenProvider tokenProvider;

  public PodGrpcClientInterceptor(ServiceTokenProvider tokenProvider) {
    this.tokenProvider = tokenProvider;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Channel next) {
    String bearer = "Bearer " + tokenProvider.token();
    return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
      @Override
      public void start(Listener<RespT> responseListener, Metadata headers) {
        headers.put(AUTHORIZATION_METADATA_KEY, bearer);
        super.start(responseListener, headers);
      }
    };
  }
}
