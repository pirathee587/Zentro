package com.zentro.identitypartner.grpc.security;

import com.zentro.security.observability.PropagationContext;
import com.zentro.security.observability.PropagationMetadata;
import io.grpc.*;
import io.micrometer.tracing.Span;
import org.springframework.stereotype.Component;

/** Diagnostic context only. Authentication remains owned by GrpcSecurityInterceptor. */
@Component
public final class GrpcPropagationInterceptor implements ServerInterceptor {
  private final PropagationContext propagation;
  public GrpcPropagationInterceptor(PropagationContext propagation) { this.propagation = propagation; }
  private static String single(Metadata headers, String name) {
    Iterable<String> values = headers.getAll(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER));
    if (values == null) return null;
    var iterator = values.iterator();
    if (!iterator.hasNext()) return null;
    String value = iterator.next();
    return iterator.hasNext() ? null : value;
  }
  @Override public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
      Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    var metadata = new PropagationMetadata(single(headers, "x-correlation-id"), single(headers, "traceparent"), single(headers, "tracestate"));
    var span = propagation.start(metadata, "grpc.server", Span.Kind.SERVER);
    ServerCall<ReqT, RespT> wrapped = new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
      @Override public void close(Status status, Metadata trailers) {
        if (!status.isOk()) span.failed();
        super.close(status, trailers);
      }
    };
    try (var scope = span.scope()) {
      ServerCall.Listener<ReqT> listener = next.startCall(wrapped, headers);
      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(listener) {
        private void scoped(Runnable callback, boolean end) {
          try (var scope = span.scope()) { callback.run(); }
          catch (RuntimeException failure) { span.failed(); span.close(); throw safeFailure(failure); }
          catch (Error failure) { span.failed(); span.close(); throw failure; }
          finally { if (end) span.close(); }
        }
        @Override public void onMessage(ReqT message) { scoped(() -> super.onMessage(message), false); }
        @Override public void onHalfClose() { scoped(super::onHalfClose, false); }
        @Override public void onReady() { scoped(super::onReady, false); }
        @Override public void onComplete() { scoped(super::onComplete, true); }
        @Override public void onCancel() { span.failed(); scoped(super::onCancel, true); }
      };
    } catch (RuntimeException failure) { span.failed(); span.close(); throw safeFailure(failure); }
    catch (Error failure) { span.failed(); span.close(); throw failure; }
  }
  private static RuntimeException safeFailure(RuntimeException failure) {
    // Preserve explicitly declared statuses. Unhandled exceptions still map to UNKNOWN,
    // but grpc-java must not log database/provider messages or their nested causes.
    return failure instanceof StatusRuntimeException ? failure : new RuntimeException("gRPC handler failed");
  }
}
