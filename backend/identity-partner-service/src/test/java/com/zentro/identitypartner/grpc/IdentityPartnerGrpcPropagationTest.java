package com.zentro.identitypartner.grpc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zentro.contracts.identity.v1.*;
import com.zentro.identitypartner.courier.dto.CourierGrpcResponse;
import com.zentro.identitypartner.courier.service.CourierProfileService;
import com.zentro.identitypartner.grpc.security.*;
import com.zentro.identitypartner.grpc.server.IdentityPartnerGrpcServer;
import com.zentro.identitypartner.grpc.service.IdentityPartnerGrpcService;
import com.zentro.security.observability.PropagationContext;
import io.grpc.*;
import io.grpc.stub.MetadataUtils;
import io.micrometer.tracing.otel.bridge.*;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.*;
import org.springframework.security.oauth2.jwt.*;

@ExtendWith(OutputCaptureExtension.class)
class IdentityPartnerGrpcPropagationTest {
  private final SdkTracerProvider provider = SdkTracerProvider.builder().build();
  private final io.opentelemetry.api.trace.Tracer sdk = provider.get("test");
  private final OtelTracer tracer = new OtelTracer(sdk, new OtelCurrentTraceContext(), event -> {});
  private IdentityPartnerGrpcServer server;
  private ManagedChannel channel;
  private final ConcurrentMap<String, String> observed = new ConcurrentHashMap<>();
  @BeforeEach void start() throws Exception {
    var couriers = mock(CourierProfileService.class);
    when(couriers.getCourierProfileForGrpc(anyString())).thenAnswer(invocation -> {
      String id = invocation.getArgument(0);
      if (id.equals("failing-courier")) throw new IllegalStateException("private-rpc-database-secret");
      observed.put(id, MDC.get("correlationId") + "|" + tracer.currentSpan().context().traceId());
      return new CourierGrpcResponse(id, true);
    });
    var decoder = mock(JwtDecoder.class);
    when(decoder.decode(anyString())).thenThrow(new BadJwtException("private-jwt-secret"));
    var propagation = new PropagationContext(tracer, new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), sdk));
    server = new IdentityPartnerGrpcServer(0, new IdentityPartnerGrpcService(couriers, mock(org.springframework.jdbc.core.JdbcTemplate.class)),
        new GrpcSecurityInterceptor("test-secret", decoder), new GrpcPropagationInterceptor(propagation));
    server.start();
    channel = ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
  }
  @AfterEach void stop() throws Exception { channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS); server.stop(); provider.close(); }
  private Metadata headers(String correlation, String trace) {
    var headers = new Metadata();
    headers.put(Metadata.Key.of("x-internal-token", Metadata.ASCII_STRING_MARSHALLER), "test-secret");
    headers.put(Metadata.Key.of("x-correlation-id", Metadata.ASCII_STRING_MARSHALLER), correlation);
    headers.put(Metadata.Key.of("traceparent", Metadata.ASCII_STRING_MARSHALLER), trace);
    return headers;
  }
  private void call(String id, Metadata headers) {
    IdentityPartnerServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
        .getCourierProfile(GetCourierProfileRequest.newBuilder().setCourierId(id).build());
  }
  @Test void concurrentCallsAndMalformedMetadataStayIsolated() throws Exception {
    try (var pool = Executors.newFixedThreadPool(4)) {
      var tasks = java.util.stream.IntStream.range(1, 13).<Callable<Void>>mapToObj(i -> () -> {
        String id = UUID.randomUUID().toString();
        String trace = "%032x".formatted(i);
        call(id, headers("call-" + i, "00-" + trace + "-0123456789abcdef-01"));
        assertThat(observed.get(id)).isEqualTo("call-" + i + "|" + trace);
        return null;
      }).toList();
      for (var result : pool.invokeAll(tasks)) result.get();
    }
    String id = UUID.randomUUID().toString();
    var malformed = headers("private-invalid@value", "private-invalid-trace");
    call(id, malformed);
    assertThat(observed.get(id)).doesNotContain("private", "call-");
    assertThatCode(() -> UUID.fromString(observed.get(id).split("\\|")[0])).doesNotThrowAnyException();
    assertThat(MDC.get("correlationId")).isNull();
  }
  @Test void rejectedAuthenticationDoesNotLogCredentialsOrJwtException(CapturedOutput output) {
    var headers = new Metadata();
    headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer private-jwt-secret");
    assertThatThrownBy(() -> call(UUID.randomUUID().toString(), headers))
        .isInstanceOf(StatusRuntimeException.class).satisfies(ex ->
            assertThat(((StatusRuntimeException) ex).getStatus().getCode()).isEqualTo(Status.Code.UNAUTHENTICATED));
    assertThat(observed).isEmpty();
    assertThat(output.getAll()).doesNotContain("private-jwt-secret", "Bearer private");
  }
  @Test void unhandledFailureKeepsUnknownStatusWithoutLeakingExceptionMessage(CapturedOutput output) {
    assertThatThrownBy(() -> call("failing-courier", headers("failed-rpc-143", "invalid")))
        .isInstanceOf(StatusRuntimeException.class).satisfies(ex ->
            assertThat(((StatusRuntimeException) ex).getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN));
    assertThat(output.getAll()).doesNotContain("private-rpc-database-secret");
  }
}
