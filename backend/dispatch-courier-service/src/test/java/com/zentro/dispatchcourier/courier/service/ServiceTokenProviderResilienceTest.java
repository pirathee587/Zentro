package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.zentro.dispatchcourier.exception.CompletionFailure;
import com.zentro.dispatchcourier.support.StubHttpServer;
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
 * Timeouts, failure handling and caching of the S3 client-credentials token request (ZD-072), built with the
 * production constructor (3 s connect, 5 s until the response headers) against a local token endpoint stub.
 */
@ExtendWith(OutputCaptureExtension.class)
class ServiceTokenProviderResilienceTest {

  private static final String ACCESS_TOKEN = "service-access-token-072";
  private static final String CLIENT_SECRET = "dispatch-client-secret-072";

  private StubHttpServer keycloak;

  @BeforeEach
  void startStub() {
    keycloak = StubHttpServer.start();
  }

  @AfterEach
  void stopStub() {
    keycloak.close();
  }

  @Test
  void tokenIsCachedUntilShortlyBeforeExpiry() {
    keycloak.respond(200, token(300));
    ServiceTokenProvider tokens = provider(keycloak.baseUrl());

    for (int i = 0; i < 3; i++) {
      assertThat(tokens.token()).isEqualTo(ACCESS_TOKEN);
    }

    assertThat(keycloak.requests()).isEqualTo(1);
  }

  @Test
  void tokensLivingThirtySecondsOrLessAreFetchedPerCall() {
    // The provider keeps a 30 s safety margin, so a 20 s token is never reused (one request per call, not more).
    keycloak.respond(200, token(20));
    ServiceTokenProvider tokens = provider(keycloak.baseUrl());

    tokens.token();
    tokens.token();

    assertThat(keycloak.requests()).isEqualTo(2);
  }

  @Test
  void slowTokenEndpointFailsWithinTheTimeoutAndStaysRetryable() {
    keycloak.respondAfter(Duration.ofMillis(6500), 200, token(300));

    long start = System.nanoTime();
    CompletionFailure failure = fail(provider(keycloak.baseUrl()));

    assertFailure(failure, "PENDING", "TOKEN_SERVICE_UNAVAILABLE");
    assertThat(elapsedMs(start)).isBetween(4500L, 7000L);
    assertThat(keycloak.requests()).isEqualTo(1);
  }

  @Test
  void tokenEndpointDownFailsQuicklyAndStaysRetryable() {
    long start = System.nanoTime();
    CompletionFailure failure = fail(provider("http://127.0.0.1:" + StubHttpServer.unusedPort()));

    assertFailure(failure, "PENDING", "TOKEN_SERVICE_UNAVAILABLE");
    // Windows takes about 1 s to report a refused local connection; still well below the 3 s connect timeout.
    assertThat(elapsedMs(start)).isLessThan(2500);
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 502, 503})
  void throttledOrFailingTokenEndpointStaysRetryableWithOneRequest(int status) {
    keycloak.respond(status, "{}");

    assertFailure(fail(provider(keycloak.baseUrl())), "PENDING", "TOKEN_REQUEST_FAILED");
    assertThat(keycloak.requests()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403})
  void rejectedClientCredentialsBlockWithOneRequest(int status) {
    keycloak.respond(status, "{\"error\":\"unauthorized_client\"}");

    assertFailure(fail(provider(keycloak.baseUrl())), "BLOCKED", "TOKEN_REQUEST_FAILED");
    assertThat(keycloak.requests()).isEqualTo(1);
  }

  @Test
  void connectionClosedWithoutReplyIsNotRepeated() {
    keycloak.closeWithoutReply();

    assertFailure(fail(provider(keycloak.baseUrl())), "PENDING", "TOKEN_SERVICE_UNAVAILABLE");
    // The JDK client never repeats a POST on its own.
    assertThat(keycloak.requests()).isEqualTo(1);
  }

  @Test
  void failedTokenRequestIsNotCachedAndTheNextCallRecovers() {
    ServiceTokenProvider tokens = provider(keycloak.baseUrl());
    keycloak.respond(503, "{}");
    fail(tokens);

    keycloak.respond(200, token(300));

    assertThat(tokens.token()).isEqualTo(ACCESS_TOKEN);
    assertThat(keycloak.requests()).isEqualTo(2);
  }

  @Test
  void failuresDoNotLogTheClientSecretOrTokens(CapturedOutput output) {
    keycloak.respond(500, "{}");
    CompletionFailure failure = fail(provider(keycloak.baseUrl()));
    fail(provider("http://127.0.0.1:" + StubHttpServer.unusedPort()));
    keycloak.respond(200, token(300));
    provider(keycloak.baseUrl()).token();

    assertThat(failure.getMessage()).doesNotContain(CLIENT_SECRET);
    assertThat(output.getAll()).doesNotContain(CLIENT_SECRET, ACCESS_TOKEN);
  }

  private static ServiceTokenProvider provider(String baseUrl) {
    return new ServiceTokenProvider(RestClient.builder(), baseUrl + "/token", "dispatch-courier-service",
        CLIENT_SECRET);
  }

  private static String token(int expiresIn) {
    return "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":" + expiresIn + ",\"token_type\":\"Bearer\"}";
  }

  private static CompletionFailure fail(ServiceTokenProvider tokens) {
    return catchThrowableOfType(tokens::token, CompletionFailure.class);
  }

  private static void assertFailure(CompletionFailure failure, String state, String code) {
    assertThat(failure).isNotNull();
    assertThat(failure.state()).isEqualTo(state);
    assertThat(failure.code()).isEqualTo(code);
  }

  private static long elapsedMs(long start) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }
}
