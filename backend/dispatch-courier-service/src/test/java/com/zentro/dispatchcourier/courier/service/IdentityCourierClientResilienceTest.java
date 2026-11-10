package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.zentro.dispatchcourier.support.StubHttpServer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/**
 * Timeouts and outage handling of the S3 -> S1 courier lookups (ZD-072). The client is built with its production
 * constructor, so the real 3 s connect and 5 s read timeouts apply, and talks to a local stub of S1.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdentityCourierClientResilienceTest {

  private static final String TOKEN = "courier-access-token-072";
  private static final UUID PROFILE_ID = UUID.fromString("3f0c2a6e-1d4b-4e8a-9b7c-5a2d1e0f9c11");
  private static final UUID USER_ID = UUID.fromString("7b1e4d2c-9a3f-4c6b-8e5d-2f0a1b3c4d55");
  private static final String COURIER = "{\"id\":\"" + PROFILE_ID + "\",\"userId\":\"" + USER_ID
      + "\",\"name\":\"Kamal Silva\",\"active\":true,\"accountStatus\":\"ACTIVE\"}";

  private StubHttpServer identity;

  @BeforeEach
  void startStub() {
    identity = StubHttpServer.start();
  }

  @AfterEach
  void stopStub() {
    identity.close();
  }

  static Stream<Arguments> lookups() {
    return Stream.of(
        Arguments.of("currentCourier", (Function<IdentityCourierClient, Object>) c -> c.currentCourier(TOKEN), COURIER),
        Arguments.of("listEligible", (Function<IdentityCourierClient, Object>) c -> c.listEligible(TOKEN),
            "[" + COURIER + "]"),
        Arguments.of("listAll", (Function<IdentityCourierClient, Object>) c -> c.listAll(TOKEN), "[" + COURIER + "]"),
        Arguments.of("requireEligible",
            (Function<IdentityCourierClient, Object>) c -> c.requireEligible(PROFILE_ID, TOKEN), COURIER));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lookups")
  void healthyIdentityAnswersWithOneRequestAndTheCallersToken(String name, Function<IdentityCourierClient, Object> call,
      String body) {
    identity.respond(200, body);

    assertThat(call.apply(client(identity.baseUrl()))).isNotNull();

    assertThat(identity.requests()).isEqualTo(1);
    assertThat(identity.lastAuthorization()).isEqualTo("Bearer " + TOKEN);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lookups")
  void slowIdentityFailsWithinTheReadTimeoutWith503(String name, Function<IdentityCourierClient, Object> call,
      String body) {
    identity.respondAfter(Duration.ofMillis(6500), 200, body);

    long start = System.nanoTime();
    ResponseStatusException error = fail(call, identity.baseUrl());

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(elapsedMs(start)).isBetween(4500L, 7000L);
    assertThat(identity.requests()).isEqualTo(1);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lookups")
  void refusedConnectionFailsFastWith503(String name, Function<IdentityCourierClient, Object> call, String body) {
    long start = System.nanoTime();
    ResponseStatusException error = fail(call, "http://127.0.0.1:" + StubHttpServer.unusedPort());

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    // Windows takes about 1 s to report a refused local connection; still well below the 3 s connect timeout.
    assertThat(elapsedMs(start)).isLessThan(2500);
  }

  @Test
  void unreachableHostFailsWithinTheConnectTimeoutWith503() {
    long start = System.nanoTime();
    // Non-routable address: either the 3 s connect timeout or an immediate network error, never a hang.
    ResponseStatusException error = fail(c -> c.currentCourier(TOKEN), "http://10.255.255.1:81");

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(elapsedMs(start)).isLessThan(5000);
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504, 429})
  void failingStatusBecomes503WithoutRetry(int status) {
    identity.respond(status, "{\"detail\":\"internal identity details\"}");

    ResponseStatusException error = fail(c -> c.listEligible(TOKEN), identity.baseUrl());

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(error.getReason()).doesNotContain("internal identity details");
    assertThat(identity.requests()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void authenticationAndAccessErrorsKeepTheirStatus(int status) {
    identity.respond(status, "{}");

    assertThat(fail(c -> c.currentCourier(TOKEN), identity.baseUrl()).getStatusCode().value()).isEqualTo(status);
    assertThat(identity.requests()).isEqualTo(1);
  }

  @Test
  void unknownCourierIs404OnlyForTheRevalidation() {
    identity.respond(404, "{}");

    assertThat(fail(c -> c.requireEligible(PROFILE_ID, TOKEN), identity.baseUrl()).getStatusCode().value())
        .isEqualTo(404);
    assertThat(fail(c -> c.currentCourier(TOKEN), identity.baseUrl()).getStatusCode().value()).isEqualTo(503);
    assertThat(identity.requests()).isEqualTo(2);
  }

  @Test
  void connectionClosedWithoutReplyBecomes503AfterAtMostOneTransportRetry() {
    identity.closeWithoutReply();

    long start = System.nanoTime();
    ResponseStatusException error = fail(c -> c.currentCourier(TOKEN), identity.baseUrl());

    assertThat(error.getStatusCode().value()).isEqualTo(503);
    assertThat(elapsedMs(start)).isLessThan(2000);
    // HttpURLConnection repeats an idempotent GET once when the connection drops before any reply.
    assertThat(identity.requests()).isBetween(1, 2);
  }

  @Test
  void readTimeoutLimitsEachReadNotTheWholeCall() {
    // Finding F1: eight 800 ms pauses (6.4 s in total) never exceed the 5 s read timeout, so the call succeeds.
    identity.trickle(200, COURIER, 8, Duration.ofMillis(800));

    long start = System.nanoTime();
    assertThat(client(identity.baseUrl()).currentCourier(TOKEN).id()).isEqualTo(PROFILE_ID);

    assertThat(elapsedMs(start)).isGreaterThan(5000);
    assertThat(identity.requests()).isEqualTo(1);
  }

  @Test
  void failuresDoNotLogTokensOrPersonalData(CapturedOutput output) {
    identity.respond(500, "{\"name\":\"Kamal Silva\"}");
    fail(c -> c.currentCourier(TOKEN), identity.baseUrl());
    identity.respond(200, COURIER.replace("\"active\":true", "\"active\":false"));
    assertThat(fail(c -> c.currentCourier(TOKEN), identity.baseUrl()).getStatusCode().value()).isEqualTo(403);
    fail(c -> c.listAll(TOKEN), "http://127.0.0.1:" + StubHttpServer.unusedPort());

    assertThat(output.getAll()).doesNotContain(TOKEN, "Kamal Silva");
  }

  private static IdentityCourierClient client(String baseUrl) {
    return new IdentityCourierClient(RestClient.builder(), baseUrl);
  }

  private static ResponseStatusException fail(Function<IdentityCourierClient, Object> call, String baseUrl) {
    return catchThrowableOfType(() -> call.apply(client(baseUrl)), ResponseStatusException.class);
  }

  private static long elapsedMs(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }
}
