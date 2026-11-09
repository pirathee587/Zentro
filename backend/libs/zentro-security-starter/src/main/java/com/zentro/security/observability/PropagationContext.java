package com.zentro.security.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.MDC;

/** Detached work starts a fresh span under stored/remote context, never under ambient worker context. */
public final class PropagationContext {
  private final Tracer tracer;
  private final Propagator propagator;
  public PropagationContext(Tracer tracer, Propagator propagator) {
    this.tracer = tracer;
    this.propagator = propagator;
  }
  public static PropagationContext noop() { return new PropagationContext(Tracer.NOOP, Propagator.NOOP); }

  public PropagationMetadata capture() {
    Map<String, String> headers = new HashMap<>();
    Span span = tracer.currentSpan();
    if (span != null) propagator.inject(span.context(), headers, Map::put);
    return new PropagationMetadata(MDC.get(CorrelationIdFilter.MDC_KEY), headers.get("traceparent"), headers.get("tracestate"));
  }

  public WorkSpan start(PropagationMetadata metadata, String name, Span.Kind kind) {
    var builder = metadata.traceparent() == null ? tracer.spanBuilder().setNoParent()
        : propagator.extract(metadata.headers(), Map::get);
    builder.name(name);
    if (kind != null) builder.kind(kind);
    return new WorkSpan(builder.start(), metadata.correlationId());
  }

  public final class WorkSpan implements AutoCloseable {
    private final Span span;
    private final String correlation;
    private final AtomicBoolean ended = new AtomicBoolean();
    private WorkSpan(Span span, String correlation) { this.span = span; this.correlation = correlation; }
    public Scope scope() { return new Scope(span, correlation); }
    public void failed() { span.tag("error", "true"); } // never record exception messages in telemetry
    @Override public void close() { if (ended.compareAndSet(false, true)) span.end(); }
  }

  public final class Scope implements AutoCloseable {
    private final String previous = MDC.get(CorrelationIdFilter.MDC_KEY);
    private final Tracer.SpanInScope traceScope;
    private Scope(Span span, String correlation) {
      traceScope = tracer.withSpan(span);
      MDC.put(CorrelationIdFilter.MDC_KEY, correlation);
    }
    @Override public void close() {
      try {
        if (previous == null) MDC.remove(CorrelationIdFilter.MDC_KEY); else MDC.put(CorrelationIdFilter.MDC_KEY, previous);
      } finally { traceScope.close(); }
    }
  }
}
