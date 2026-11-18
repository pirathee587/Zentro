package com.zentro.dispatchcourier.grpc.security;

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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Validates incoming OAuth2 SERVICE JWTs on {@code DispatchCourierService} gRPC calls.
 */
@Component
public class DispatchGrpcSecurityInterceptor implements ServerInterceptor {
  private static final Logger log = LoggerFactory.getLogger(DispatchGrpcSecurityInterceptor.class);

  public static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
  public static final Context.Key<String> CALLER_IDENTITY_CONTEXT_KEY =
      Context.key("callerIdentity");

  private final JwtDecoder jwtDecoder;

  @Autowired
  public DispatchGrpcSecurityInterceptor(@Autowired(required = false) JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call,
      Metadata headers,
      ServerCallHandler<ReqT, RespT> next) {
    String authHeader = headers.get(AUTHORIZATION_METADATA_KEY);
    if (authHeader == null || !authHeader.toLowerCase().startsWith("bearer ")) {
      log.warn("Unauthenticated gRPC call rejected: method={}", call.getMethodDescriptor().getFullMethodName());
      call.close(Status.UNAUTHENTICATED.withDescription("Missing Bearer service token"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    if (jwtDecoder == null) {
      call.close(Status.UNAVAILABLE.withDescription("JWT decoder unavailable"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    String token = authHeader.substring(7).trim();
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(token);
    } catch (JwtException ex) {
      log.warn("gRPC JWT validation failed: method={} category=invalid_jwt", call.getMethodDescriptor().getFullMethodName());
      call.close(Status.UNAUTHENTICATED.withDescription("Invalid or expired service JWT"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    if (!hasServiceRole(jwt)) {
      call.close(Status.PERMISSION_DENIED.withDescription("Caller lacks SERVICE role"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    if (!"zentro-tracking-service".equals(jwt.getClaimAsString("azp"))
        || !"GetParcelAssignmentSummary".equals(call.getMethodDescriptor().getBareMethodName())) {
      call.close(Status.PERMISSION_DENIED.withDescription("Service client is not authorized for this RPC"), new Metadata());
      return new ServerCall.Listener<>() {};
    }
    String caller = jwt.getSubject() != null && !jwt.getSubject().isBlank() ? jwt.getSubject() : "service-account";
    Context context = Context.current().withValue(CALLER_IDENTITY_CONTEXT_KEY, caller);
    return Contexts.interceptCall(context, call, headers, next);
  }

  private static boolean hasServiceRole(Jwt jwt) {
    Object realmAccess = jwt.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof Collection<?> roles) {
      for (Object role : roles) {
        if (role instanceof String s && "SERVICE".equalsIgnoreCase(s.trim())) {
          return true;
        }
      }
    }
    return false;
  }
}
