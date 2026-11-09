package com.zentro.security.observability;

import static org.assertj.core.api.Assertions.*;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PropagationContextTest {
  private static final String TRACE = "0123456789abcdef0123456789abcdef";
  private final SdkTracerProvider provider = SdkTracerProvider.builder().build();
  private final io.opentelemetry.api.trace.Tracer sdk = provider.get("test");
  private final OtelTracer tracer = new OtelTracer(sdk, new OtelCurrentTraceContext(), event -> {});
  private final PropagationContext propagation = new PropagationContext(tracer,
      new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), sdk));
  @AfterEach void close() { provider.close(); MDC.remove("correlationId"); }
  private PropagationMetadata metadata(String id) { return new PropagationMetadata(id, "00-" + TRACE + "-0123456789abcdef-01", "vendor=value"); }

  @Test void validatesAndCanonicalizesOnlyDiagnosticHeaders() {
    var valid = metadata("request-123");
    assertThat(valid.headers()).containsEntry("X-Correlation-Id", "request-123").containsEntry("tracestate", "vendor=value");
    for (String bad : List.of("secret@example.test", "a\nb", "a".repeat(129))) {
      var invalid = new PropagationMetadata(bad, "secret-invalid-trace", "password=bad\n");
      assertThatCode(() -> UUID.fromString(invalid.correlationId())).doesNotThrowAnyException();
      assertThat(invalid.traceparent()).isNull();
      assertThat(invalid.tracestate()).isNull();
      assertThat(invalid.toString()).doesNotContain(bad, "password");
    }
  }

  @Test void startsNewSpanAndRestoresOuterContextAfterException() {
    var outer = tracer.nextSpan().name("outer").start();
    try (var outerScope = tracer.withSpan(outer)) {
      MDC.put("correlationId", "outer");
      var work = propagation.start(metadata("work-123"), "test.process", Span.Kind.CONSUMER);
      assertThatThrownBy(() -> {
        try (work; var scope = work.scope()) {
          assertThat(tracer.currentSpan().context().traceId()).isEqualTo(TRACE);
          assertThat(tracer.currentSpan().context().spanId()).isNotEqualTo("0123456789abcdef");
          assertThat(propagation.capture().correlationId()).isEqualTo("work-123");
          throw new IllegalStateException("private-exception");
        }
      }).isInstanceOf(IllegalStateException.class);
      assertThat(MDC.get("correlationId")).isEqualTo("outer");
      assertThat(tracer.currentSpan().context().spanId()).isEqualTo(outer.context().spanId());
      try (var legacy = propagation.start(new PropagationMetadata(null, null, null), "legacy", null);
           var scope = legacy.scope()) {
        assertThat(tracer.currentSpan().context().traceId()).isNotEqualTo(outer.context().traceId());
      }
    } finally { outer.end(); }
    assertThat(tracer.currentSpan()).isNull();
  }

  @Test void kafkaRetriesAndMalformedHeadersNeverLeakThreadState() throws Exception {
    var interceptor = new KafkaPropagationInterceptor(propagation);
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = java.util.stream.IntStream.range(0, 40).<java.util.concurrent.Callable<Void>>mapToObj(i -> () -> {
        var record = new ConsumerRecord<Object, Object>("parcel.created", 0, i, "key", "private-payload");
        metadata("record-" + i).headers().forEach((key, value) -> record.headers().add(key, value.getBytes(StandardCharsets.UTF_8)));
        for (int attempt = 0; attempt < 2; attempt++) {
          assertThat(interceptor.intercept(record, null)).isSameAs(record);
          assertThat(tracer.currentSpan().context().traceId()).isEqualTo(TRACE);
          assertThat(MDC.get("correlationId")).isEqualTo("record-" + i);
          interceptor.failure(record, new IllegalStateException("secret"), null);
          interceptor.afterRecord(record, null);
          assertThat(MDC.get("correlationId")).isNull();
          assertThat(tracer.currentSpan()).isNull();
        }
        record.headers().add("X-Correlation-Id", "duplicate-secret".getBytes(StandardCharsets.UTF_8));
        record.headers().remove("traceparent");
        record.headers().add("traceparent", "invalid-secret".getBytes(StandardCharsets.UTF_8));
        interceptor.intercept(record, null);
        assertThat(MDC.get("correlationId")).isNotEqualTo("duplicate-secret");
        assertThat(tracer.currentSpan().context().traceId()).isNotEqualTo(TRACE);
        interceptor.clearThreadState(null);
        assertThat(MDC.get("correlationId")).isNull();
        return null;
      }).toList();
      for (var result : pool.invokeAll(tasks)) result.get();
    }
  }
}
