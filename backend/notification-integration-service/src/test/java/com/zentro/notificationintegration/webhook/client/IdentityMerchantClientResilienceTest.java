package com.zentro.notificationintegration.webhook.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.zentro.notificationintegration.support.StubHttpServer;
import com.zentro.notificationintegration.webhook.exception.MerchantVerificationException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.client.RestClient;

/**
 * Timeouts and outage handling of the S5 -> S1 merchant check used by webhook subscriptions (ZD-072). The client
 * is built with its production constructor, so the real 3 s connect and 5 s read timeouts apply, and talks to a
 * local stub of S1.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdentityMerchantClientResilienceTest {

  private static final String TOKEN = "merchant-access-token-072";
  private static final String APPROVED = """
      {"id":"8a6d1b8e-6f0e-4a57-9a43-0d6b8e2c9f10","email":"shop@example.test","name":"Nimal Perera",
       "phone":"0771234567","accountStatus":"ACTIVE","merchantStatus":"APPROVED","roles":["MERCHANT"]}""";

  private StubHttpServer identity;

  @BeforeEach
  void startStub() {
    identity = StubHttpServer.start();
  }

  @AfterEach
  void stopStub() {
    identity.close();
  }

  @Test
  void approvedMerchantPassesWithOneRequestAndTheCallersToken() {
    identity.respond(200, APPROVED);

    assertThatCode(() -> client(identity.baseUrl()).requireApprovedMerchant(TOKEN)).doesNotThrowAnyException();

    assertThat(identity.requests()).isEqualTo(1);
    assertThat(identity.lastAuthorization()).isEqualTo("Bearer " + TOKEN);
  }

  @Test
  void slowIdentityFailsWithinTheReadTimeoutAsUnavailable() {
    identity.respondAfter(Duration.ofMillis(6500), 200, APPROVED);

    long start = System.nanoTime();
    MerchantVerificationException error = check(identity.baseUrl());

    assertUnavailable(error);
    assertThat(elapsedMs(start)).isBetween(4500L, 7000L);
    assertThat(identity.requests()).isEqualTo(1);
  }

  @Test
  void refusedConnectionFailsFastAsUnavailable() {
    long start = System.nanoTime();
    MerchantVerificationException error = check("http://127.0.0.1:" + StubHttpServer.unusedPort());

    assertUnavailable(error);
    // Windows takes about 1 s to report a refused local connection; still well below the 3 s connect timeout.
    assertThat(elapsedMs(start)).isLessThan(2500);
  }

  @Test
  void unreachableHostFailsWithinTheConnectTimeoutAsUnavailable() {
    long start = System.nanoTime();
    // Non-routable address: either the 3 s connect timeout or an immediate network error, never a hang.
    MerchantVerificationException error = check("http://10.255.255.1:81");

    assertUnavailable(error);
    assertThat(elapsedMs(start)).isLessThan(5000);
  }

  @ParameterizedTest
  @ValueSource(ints = {500, 502, 503, 504, 429, 404})
  void failingOrUnexpectedStatusIsUnavailableWithoutRetry(int status) {
    identity.respond(status, "{\"detail\":\"internal identity details\"}");

    MerchantVerificationException error = check(identity.baseUrl());

    assertUnavailable(error);
    assertThat(error.getMessage()).doesNotContain("internal identity details");
    assertThat(identity.requests()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void authenticationAndAccessErrorsAreForbidden(int status) {
    identity.respond(status, "{}");

    MerchantVerificationException error = check(identity.baseUrl());

    assertThat(error.status().value()).isEqualTo(403);
    assertThat(error.code()).isEqualTo("MERCHANT_NOT_APPROVED");
    assertThat(identity.requests()).isEqualTo(1);
  }

  @Test
  void connectionClosedWithoutReplyIsUnavailableAfterAtMostOneTransportRetry() {
    identity.closeWithoutReply();

    long start = System.nanoTime();
    MerchantVerificationException error = check(identity.baseUrl());

    assertUnavailable(error);
    assertThat(elapsedMs(start)).isLessThan(2000);
    // HttpURLConnection repeats an idempotent GET once when the connection drops before any reply.
    assertThat(identity.requests()).isBetween(1, 2);
  }

  @Test
  void readTimeoutLimitsEachReadNotTheWholeCall() {
    // Finding F1: eight 800 ms pauses (6.4 s in total) never exceed the 5 s read timeout, so the call succeeds.
    identity.trickle(200, APPROVED, 8, Duration.ofMillis(800));

    long start = System.nanoTime();
    assertThatCode(() -> client(identity.baseUrl()).requireApprovedMerchant(TOKEN)).doesNotThrowAnyException();

    assertThat(elapsedMs(start)).isGreaterThan(5000);
    assertThat(identity.requests()).isEqualTo(1);
  }

  @Test
  void failuresDoNotLogTokensOrPersonalData(CapturedOutput output) {
    identity.respond(500, "{\"name\":\"Nimal Perera\"}");
    check(identity.baseUrl());
    identity.respond(200, APPROVED.replace("\"ACTIVE\"", "\"SUSPENDED\""));
    assertThat(check(identity.baseUrl()).status().value()).isEqualTo(403);
    check("http://127.0.0.1:" + StubHttpServer.unusedPort());

    assertThat(output.getAll()).doesNotContain(TOKEN, "Nimal Perera", "0771234567", "shop@example.test");
  }

  private static IdentityMerchantClient client(String baseUrl) {
    return new IdentityMerchantClient(RestClient.builder(), baseUrl);
  }

  private static MerchantVerificationException check(String baseUrl) {
    return catchThrowableOfType(() -> client(baseUrl).requireApprovedMerchant(TOKEN),
        MerchantVerificationException.class);
  }

  private static void assertUnavailable(MerchantVerificationException error) {
    assertThat(error).isNotNull();
    assertThat(error.status().value()).isEqualTo(503);
    assertThat(error.code()).isEqualTo("IDENTITY_UNAVAILABLE");
  }

  private static long elapsedMs(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }
}
