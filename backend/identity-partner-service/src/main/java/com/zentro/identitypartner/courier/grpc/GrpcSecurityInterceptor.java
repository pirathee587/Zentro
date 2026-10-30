package com.zentro.identitypartner.courier.grpc;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.Collection;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
public class GrpcSecurityInterceptor implements ServerInterceptor {
  private static final Logger log = LoggerFactory.getLogger(GrpcSecurityInterceptor.class);

  public static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> INTERNAL_TOKEN_METADATA_KEY =
      Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER);
  public static final Metadata.Key<String> INTERNAL_SERVICE_METADATA_KEY =
      Metadata.Key.of("x-internal-service", Metadata.ASCII_STRING_MARSHALLER);

  public static final Context.Key<String> CALLER_IDENTITY_CONTEXT_KEY =
      Context.key("callerIdentity");

  private final String internalToken;
  private final JwtDecoder jwtDecoder;

  public GrpcSecurityInterceptor(
      @Value("${zentro.security.internal-token:zentro-internal-service-secret}") String internalToken,
      @Autowired(required = false) JwtDecoder jwtDecoder) {
    this.internalToken = internalToken;
    this.jwtDecoder = jwtDecoder;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {

    String authHeader = headers.get(AUTHORIZATION_METADATA_KEY);
    String internalTokenHeader = headers.get(INTERNAL_TOKEN_METADATA_KEY);
    String internalServiceHeader = headers.get(INTERNAL_SERVICE_METADATA_KEY);

    // 1. Check shared internal service token
    if (internalTokenHeader != null && internalTokenHeader.equals(internalToken)) {
      String caller = internalServiceHeader != null ? internalServiceHeader : "internal-service";
      Context context = Context.current().withValue(CALLER_IDENTITY_CONTEXT_KEY, caller);
      return Contexts.interceptCall(context, call, headers, next);
    }

    // 2. Check Bearer JWT token if present
    if (authHeader != null && authHeader.toLowerCase().startsWith("bearer ")) {
      String token = authHeader.substring(7).trim();
      if (jwtDecoder != null) {
        try {
          Jwt jwt = jwtDecoder.decode(token);
          if (isAuthorizedInternalRole(jwt)) {
            String caller = jwt.getSubject() != null ? jwt.getSubject() : "jwt-authenticated-service";
            Context context = Context.current().withValue(CALLER_IDENTITY_CONTEXT_KEY, caller);
            return Contexts.interceptCall(context, call, headers, next);
          } else {
            call.close(Status.PERMISSION_DENIED.withDescription("Caller does not have required internal service authority"), new Metadata());
            return new ServerCall.Listener<ReqT>() {};
          }
        } catch (JwtException e) {
          log.warn("Invalid JWT in gRPC call: {}", e.getMessage());
          call.close(Status.UNAUTHENTICATED.withDescription("Invalid JWT credentials"), new Metadata());
          return new ServerCall.Listener<ReqT>() {};
        }
      }
    }

    // Unauthenticated
    log.warn("Unauthorized gRPC call rejected to method: {}", call.getMethodDescriptor().getFullMethodName());
    call.close(Status.UNAUTHENTICATED.withDescription("Missing or invalid internal authentication credentials"), new Metadata());
    return new ServerCall.Listener<ReqT>() {};
  }

  private boolean isAuthorizedInternalRole(Jwt jwt) {
    Object realmAccess = jwt.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
      for (Object r : roles) {
        if (r instanceof String s) {
          String upper = s.trim().toUpperCase();
          if ("SERVICE".equals(upper) || "DISPATCHER".equals(upper)) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
