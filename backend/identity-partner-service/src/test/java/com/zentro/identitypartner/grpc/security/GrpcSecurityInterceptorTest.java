package com.zentro.identitypartner.grpc.security;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.grpc.*;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class GrpcSecurityInterceptorTest {
  static final String SYNTHETIC_TOKEN = "synthetic-nonworking-shared-token";
  @SuppressWarnings("unchecked")
  ServerCall<String, String> call() {
    ServerCall<String, String> call = mock(ServerCall.class);
    var marshaller = new MethodDescriptor.Marshaller<String>() {
      public InputStream stream(String value) { return InputStream.nullInputStream(); }
      public String parse(InputStream stream) { return ""; }
    };
    when(call.getMethodDescriptor()).thenReturn(MethodDescriptor.<String,String>newBuilder()
        .setType(MethodDescriptor.MethodType.UNARY).setFullMethodName("synthetic.Service/Check")
        .setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build());
    return call;
  }
  @SuppressWarnings("unchecked") ServerCallHandler<String, String> next() {
    ServerCallHandler<String,String> next = mock(ServerCallHandler.class);
    when(next.startCall(any(), any())).thenReturn(new ServerCall.Listener<>() {});
    return next;
  }
  @Test void missingOrWhitespaceConfigurationRejectsBlankAndSuppliedSharedHeaders() {
    for (String configured : Arrays.asList(null, "", " ")) for (String supplied : List.of("", " ", SYNTHETIC_TOKEN)) {
      var call = call(); var next = next(); var headers = new Metadata();
      headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, supplied);
      new GrpcSecurityInterceptor(configured, null).interceptCall(call, headers, next);
      verify(next, never()).startCall(any(), any());
      verify(call).close(argThat(status -> status.getCode() == Status.Code.UNAUTHENTICATED), any());
    }
  }
  @Test void anExplicitMatchingSharedTokenStillAuthenticates() {
    var call = call(); var next = next(); var headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, SYNTHETIC_TOKEN);
    new GrpcSecurityInterceptor(SYNTHETIC_TOKEN, null).interceptCall(call, headers, next);
    verify(next).startCall(any(), any()); verify(call, never()).close(any(), any());
  }
  @Test void anIncorrectExplicitSharedTokenIsRejected() {
    var call = call(); var next = next(); var headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, "synthetic-wrong-token");
    new GrpcSecurityInterceptor(SYNTHETIC_TOKEN, null).interceptCall(call, headers, next);
    verify(next, never()).startCall(any(), any());
    verify(call).close(argThat(status -> status.getCode() == Status.Code.UNAUTHENTICATED), any());
  }
  @Test void theExistingServiceJwtPathWorksWithoutASharedToken() {
    var call = call(); var next = next(); var headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.AUTHORIZATION_METADATA_KEY, "Bearer synthetic-jwt");
    JwtDecoder decoder = mock(JwtDecoder.class);
    when(decoder.decode("synthetic-jwt")).thenReturn(Jwt.withTokenValue("synthetic-jwt").header("alg", "synthetic")
        .subject("synthetic-service").claim("realm_access", Map.of("roles", List.of("SERVICE"))).build());
    new GrpcSecurityInterceptor("", decoder).interceptCall(call, headers, next);
    verify(next).startCall(any(), any()); verify(call, never()).close(any(), any());
  }
}
