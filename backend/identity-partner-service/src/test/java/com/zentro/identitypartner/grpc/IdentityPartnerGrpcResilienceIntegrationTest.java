package com.zentro.identitypartner.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.zentro.contracts.identity.v1.GetCourierProfileRequest;
import com.zentro.contracts.identity.v1.GetMerchantStatusRequest;
import com.zentro.contracts.identity.v1.IdentityPartnerServiceGrpc;
import com.zentro.identitypartner.courier.service.CourierProfileService;
import com.zentro.identitypartner.grpc.security.GrpcSecurityInterceptor;
import com.zentro.identitypartner.grpc.server.IdentityPartnerGrpcServer;
import com.zentro.identitypartner.grpc.service.IdentityPartnerGrpcService;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Deadline, unavailability and retry behaviour of the S1 gRPC server (ZD-072). Uses the real server, interceptor
 * and service on a random port with a plain test client; the database and courier service are mocked.
 */
class IdentityPartnerGrpcResilienceIntegrationTest {

  private static final String INTERNAL_SECRET = "test-internal-secret-072";

  private CourierProfileService courierProfileService;
  private JdbcTemplate jdbc;
  private IdentityPartnerGrpcServer server;
  private ManagedChannel channel;

  @BeforeEach
  void setUp() throws IOException {
    courierProfileService = mock(CourierProfileService.class);
    jdbc = mock(JdbcTemplate.class);
    server = new IdentityPartnerGrpcServer(0, new IdentityPartnerGrpcService(courierProfileService, jdbc),
        new GrpcSecurityInterceptor(INTERNAL_SECRET, null));
    server.start();
    channel = channelTo(server.getPort());
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    channel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    server.stop();
  }

  @Test
  @DisplayName("A short client deadline fails with DEADLINE_EXCEEDED in time and cancels the server context")
  void shortDeadlineAgainstSlowQueryFailsWithDeadlineExceeded() throws InterruptedException {
    AtomicReference<Deadline> serverDeadline = new AtomicReference<>();
    AtomicBoolean cancelSeen = new AtomicBoolean();
    AtomicBoolean interrupted = new AtomicBoolean();
    CountDownLatch finished = new CountDownLatch(1);
    when(jdbc.queryForObject(anyString(), eq(String.class), eq("slow-merchant"))).thenAnswer(invocation -> {
      Context context = Context.current();
      serverDeadline.set(context.getDeadline());
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (System.nanoTime() < end && !cancelSeen.get()) {
        cancelSeen.set(context.isCancelled());
        Thread.sleep(10);
      }
      Thread.sleep(200);
      interrupted.set(Thread.currentThread().isInterrupted());
      finished.countDown();
      return "APPROVED";
    });

    long start = System.nanoTime();
    StatusRuntimeException error = catchThrowableOfType(() -> stub(channel)
        .withDeadlineAfter(300, TimeUnit.MILLISECONDS)
        .getMerchantStatus(merchant("slow-merchant")), StatusRuntimeException.class);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    assertThat(elapsedMs).isGreaterThanOrEqualTo(250).isLessThan(2000);
    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    // The deadline travels to the server, which sees the call cancelled. The service does not check it, so the
    // worker thread is not interrupted and the query would run to the end (finding F3).
    assertThat(serverDeadline.get()).isNotNull();
    assertThat(cancelSeen).isTrue();
    assertThat(interrupted).isFalse();
    verify(jdbc, times(1)).queryForObject(anyString(), eq(String.class), eq("slow-merchant"));
  }

  @Test
  @DisplayName("A stopped server returns UNAVAILABLE quickly instead of waiting for the deadline")
  void stoppedServerReturnsUnavailableQuickly() {
    server.stop();

    long start = System.nanoTime();
    StatusRuntimeException error = catchThrowableOfType(() -> stub(channel)
        .withDeadlineAfter(10, TimeUnit.SECONDS)
        .getMerchantStatus(merchant("any-merchant")), StatusRuntimeException.class);

    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
    verifyNoInteractions(jdbc);
  }

  @Test
  @DisplayName("A port with nothing listening returns UNAVAILABLE quickly")
  void nothingListeningReturnsUnavailable() throws Exception {
    int freePort;
    try (ServerSocket socket = new ServerSocket(0)) {
      freePort = socket.getLocalPort();
    }
    ManagedChannel deadChannel = channelTo(freePort);
    try {
      long start = System.nanoTime();
      StatusRuntimeException error = catchThrowableOfType(() -> stub(deadChannel)
          .withDeadlineAfter(10, TimeUnit.SECONDS)
          .getCourierProfile(courier(UUID.randomUUID().toString())), StatusRuntimeException.class);

      assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3000);
    } finally {
      deadChannel.shutdownNow().awaitTermination(2, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("A database failure returns UNKNOWN once, without retries and without internal details")
  void databaseFailureIsNotRetriedAndDoesNotLeakDetails() {
    when(jdbc.queryForObject(anyString(), eq(String.class), eq("broken-merchant")))
        .thenThrow(new DataAccessResourceFailureException("connection to db-host:5432 refused"));

    StatusRuntimeException error = catchThrowableOfType(() -> stub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .getMerchantStatus(merchant("broken-merchant")), StatusRuntimeException.class);

    // No mapping to UNAVAILABLE or INTERNAL exists, so gRPC reports UNKNOWN (finding F3).
    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
    assertThat(String.valueOf(error.getStatus().getDescription())).doesNotContain("db-host", "5432");
    verify(jdbc, timeout(500).times(1)).queryForObject(anyString(), eq(String.class), eq("broken-merchant"));
  }

  @Test
  @DisplayName("A failing courier lookup reaches the service exactly once")
  void failingCourierLookupIsNotRetried() {
    String courierId = UUID.randomUUID().toString();
    when(courierProfileService.getCourierProfileForGrpc(courierId))
        .thenThrow(new IllegalStateException("courier store unavailable"));

    StatusRuntimeException error = catchThrowableOfType(() -> stub(channel)
        .withDeadlineAfter(5, TimeUnit.SECONDS)
        .getCourierProfile(courier(courierId)), StatusRuntimeException.class);

    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.UNKNOWN);
    assertThat(String.valueOf(error.getStatus().getDescription())).doesNotContain("courier store");
    verify(courierProfileService, timeout(500).times(1)).getCourierProfileForGrpc(courierId);
  }

  @Test
  @DisplayName("Blank merchant ids return NOT_FOUND without a database call (no INVALID_ARGUMENT today)")
  void blankMerchantIdReturnsNotFoundWithoutDatabase() {
    for (String blank : new String[] {"", "   "}) {
      var response = stub(channel).withDeadlineAfter(5, TimeUnit.SECONDS).getMerchantStatus(merchant(blank));
      assertThat(response.getStatus()).isEqualTo("NOT_FOUND");
    }
    verifyNoInteractions(jdbc);
  }

  @Test
  @DisplayName("An expired deadline is rejected before authentication or the database are reached")
  void alreadyExpiredDeadlineNeverReachesTheService() {
    StatusRuntimeException error = catchThrowableOfType(() -> stub(channel)
        .withDeadline(Deadline.after(-1, TimeUnit.MILLISECONDS))
        .getMerchantStatus(merchant("any-merchant")), StatusRuntimeException.class);

    assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.DEADLINE_EXCEEDED);
    verifyNoInteractions(jdbc, courierProfileService);
  }

  private static ManagedChannel channelTo(int port) {
    return ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
  }

  private static IdentityPartnerServiceGrpc.IdentityPartnerServiceBlockingStub stub(ManagedChannel channel) {
    Metadata headers = new Metadata();
    headers.put(GrpcSecurityInterceptor.INTERNAL_TOKEN_METADATA_KEY, INTERNAL_SECRET);
    headers.put(GrpcSecurityInterceptor.INTERNAL_SERVICE_METADATA_KEY, "resilience-test");
    return IdentityPartnerServiceGrpc.newBlockingStub(channel)
        .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));
  }

  private static GetMerchantStatusRequest merchant(String id) {
    return GetMerchantStatusRequest.newBuilder().setMerchantId(id).build();
  }

  private static GetCourierProfileRequest courier(String id) {
    return GetCourierProfileRequest.newBuilder().setCourierId(id).build();
  }
}
