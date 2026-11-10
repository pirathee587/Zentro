package com.zentro.notificationintegration.support;

import com.zentro.security.observability.*;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

public final class PropagationTestSupport implements AutoCloseable {
  public static final String TRACE = "0123456789abcdef0123456789abcdef";
  private final SdkTracerProvider provider = SdkTracerProvider.builder().build();
  private final io.opentelemetry.api.trace.Tracer sdk = provider.get("test");
  public final OtelTracer tracer = new OtelTracer(sdk, new OtelCurrentTraceContext(), event -> {});
  public final PropagationContext context = new PropagationContext(tracer,
      new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), sdk));
  public PropagationMetadata metadata(String id) { return new PropagationMetadata(id, "00-" + TRACE + "-0123456789abcdef-01", null); }
  @Override public void close() { provider.close(); }
}
