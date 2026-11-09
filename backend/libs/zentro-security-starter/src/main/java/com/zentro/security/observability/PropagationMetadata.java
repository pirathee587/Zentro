package com.zentro.security.observability;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Only bounded, validated diagnostics; no baggage, credentials, or business payload. */
public record PropagationMetadata(String correlationId, String traceparent, String tracestate) {
  public static final String CORRELATION = CorrelationIdFilter.HEADER;
  private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
    public Iterable<String> keys(Map<String, String> carrier) { return carrier.keySet(); }
    public String get(Map<String, String> carrier, String key) { return carrier.get(key); }
  };

  public PropagationMetadata {
    correlationId = CorrelationIdFilter.isValid(correlationId) ? correlationId : UUID.randomUUID().toString();
    Map<String, String> input = new HashMap<>();
    if (safe(traceparent, 256)) input.put("traceparent", traceparent);
    if (safe(tracestate, 512)) input.put("tracestate", tracestate);
    Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.root(), input, GETTER);
    Map<String, String> canonical = new HashMap<>();
    if (Span.fromContext(extracted).getSpanContext().isValid()) {
      W3CTraceContextPropagator.getInstance().inject(extracted, canonical, Map::put);
    }
    traceparent = canonical.get("traceparent");
    tracestate = canonical.get("tracestate");
  }

  private static boolean safe(String value, int limit) {
    return value != null && value.length() <= limit && value.chars().allMatch(c -> c >= 32 && c <= 126);
  }

  public Map<String, String> headers() {
    Map<String, String> headers = new HashMap<>();
    headers.put(CORRELATION, correlationId);
    if (traceparent != null) headers.put("traceparent", traceparent);
    if (tracestate != null) headers.put("tracestate", tracestate);
    return headers;
  }

  @Override public String toString() { return "PropagationMetadata[correlationId=" + correlationId + "]"; }
}
