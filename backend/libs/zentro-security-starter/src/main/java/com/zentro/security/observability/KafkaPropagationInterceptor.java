package com.zentro.security.observability;

import io.micrometer.tracing.Span;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.listener.RecordInterceptor;

/** One span per listener attempt; thread state is removed even when processing fails. */
public final class KafkaPropagationInterceptor implements RecordInterceptor<Object, Object> {
  private final PropagationContext propagation;
  private final ThreadLocal<State> current = new ThreadLocal<>();
  private record State(PropagationContext.WorkSpan span, PropagationContext.Scope scope) {}
  public KafkaPropagationInterceptor(PropagationContext propagation) { this.propagation = propagation; }
  public static PropagationMetadata metadata(Headers headers) {
    return new PropagationMetadata(single(headers, PropagationMetadata.CORRELATION, 128),
        single(headers, "traceparent", 256), single(headers, "tracestate", 512));
  }
  private static String single(Headers headers, String name, int max) {
    var values = headers.headers(name).iterator();
    if (!values.hasNext()) return null;
    byte[] value = values.next().value();
    if (values.hasNext() || value == null || value.length > max) return null;
    return new String(value, StandardCharsets.UTF_8);
  }
  @Override public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
    clearThreadState(consumer);
    var span = propagation.start(metadata(record.headers()), "kafka.process", Span.Kind.CONSUMER);
    current.set(new State(span, span.scope()));
    return record;
  }
  @Override public void failure(ConsumerRecord<Object, Object> record, Exception ex, Consumer<Object, Object> consumer) {
    State state = current.get();
    if (state != null) state.span.failed();
  }
  @Override public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) { clearThreadState(consumer); }
  @Override public void clearThreadState(Consumer<?, ?> consumer) {
    State state = current.get();
    current.remove();
    if (state != null) { try { state.scope.close(); } finally { state.span.close(); } }
  }
}
