package com.zentro.dispatchcourier.courier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.zentro.dispatchcourier.exception.CompletionFailure;
import com.zentro.dispatchcourier.support.StubHttpServer;
import java.time.Duration;
import java.util.UUID;
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
 * Timeouts and outage handling of the S3 -> S2 delivery confirmation (ZD-072). The status client and the token
 * provider are built with their production constructors (3 s connect, 5 s until the response headers) and talk to
 * local stubs of S2 and the Keycloak token endpoint. Retries are owned by ProofCompletionWorker, so each call here
 * must make exactly one attempt.
 */
@ExtendWith(OutputCaptureExtension.class)
class ParcelStatusClientResilienceTest {

  private static final String SERVICE_TOKEN = "service-access-token-072";
  private static final String CLIENT_SECRET = "dispatch-client-secret-072";
  private static final String TOKEN_BODY =
      "{\"access_token\":\"" + SERVICE_TOKEN + "\",\"expires_in\":300,\"token_type\":\"Bearer\"}";

  private final UUID parcelId = UUID.randomUUID();
  private StubHttpServer keycloak;
  private StubHttpServer parcels;

  @BeforeEach
  void startStubs() {
    keycloak = StubHttpServer.start();
    keycloak.respond(200, TOKEN_BODY);
    parcels = StubHttpServer.start();
    parcels.respond(200, delivered());
  }

  @AfterEach
  void stopStubs() {
    keycloak.close();
    parcels.close();
  }

  @Test
  void deliveredWithOneTokenRequestAndOneStatusRequest() {
    assertThatCode(() -> client(parcels.baseUrl()).complete(parcelId)).doesNotThrowAnyException();

    assertThat(keycloak.requests()).isEqualTo(1);
    assertThat(parcels.requests()).isEqualTo(1);
    assertThat(parcels.lastAuthorization()).isEqualTo("Bearer " + SERVICE_TOKEN);
  }

  @Test
  void cachedServiceTokenIsReusedAcrossCompletions() {
    ParcelStatusClient client = client(parcels.baseUrl());

    for (int i = 0; i < 3; i++) {
      client.complete(parcelId);
    }

    assertThat(keycloak.requests()).isEqualTo(1);
    assertThat(parcels.requests()).isEqualTo(3);
  }

  @Test
  void slowParcelServiceLeavesTheDeliveryPendingWithinTheTimeout() {
    parcels.respondAfter(Duration.ofMillis(6500), 200, delivered());

    long start = System.nanoTime();
    CompletionFailure failure = fail(parcels.baseUrl());

    assertFailure(failure, "PENDING", "PARCEL_SERVICE_UNAVAILABLE");
    assertThat(elapsedMs(start)).isBetween(4500L, 7000L);
    assertThat(parcels.requests()).isEqualTo(1);
  }

  @Test
  void parcelServiceDownLeavesTheDeliveryPendingQuickly() {
    long start = System.nanoTime();
    CompletionFailure failure = fail("http://127.0.0.1:" + StubHttpServer.unusedPort());

    assertFailure(failure, "PENDING", "PARCEL_SERVICE_UNAVAILABLE");
    // Windows takes about 1 s to report a refused local connection; still well below the 3 s connect timeout.
    assertThat(elapsedMs(start)).isLessThan(2500);
  }

  @Test
  void unreachableParcelServiceFailsWithinTheConnectTimeout() {
    long start = System.nanoTime();
    // Non-routable address: either the 3 s connect timeout or an immediate network error, never a hang.
    CompletionFailure failure = fail("http://10.255.255.1:81");

    assertFailure(failure, "PENDING", "PARCEL_SERVICE_UNAVAILABLE");
    assertThat(elapsedMs(start)).isLessThan(5000);
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 502, 503, 504})
  void throttledOrFailingParcelServiceStaysPendingWithOneAttempt(int status) {
    parcels.respond(status, "{\"detail\":\"private dependency details\"}");

    CompletionFailure failure = fail(parcels.baseUrl());

    assertFailure(failure, "PENDING", "PARCEL_SERVICE_UNAVAILABLE");
    assertThat(failure.getMessage()).doesNotContain("private");
    assertThat(parcels.requests()).isEqualTo(1);
  }

  @Test
  void rejectedTransitionIsNotRetried() {
    parcels.respond(409, "{}");

    assertFailure(fail(parcels.baseUrl()), "REJECTED", "DELIVERY_TRANSITION_REJECTED");
    assertThat(parcels.requests()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 403, 404, 422})
  void otherClientErrorsBlockTheDeliveryWithOneAttempt(int status) {
    parcels.respond(status, "{}");

    assertFailure(fail(parcels.baseUrl()), "BLOCKED", "PARCEL_REQUEST_REJECTED");
    assertThat(parcels.requests()).isEqualTo(1);
  }

  @Test
  void rejectedServiceTokenIsDroppedAndFetchedAgainOnTheNextAttempt() {
    ParcelStatusClient client = client(parcels.baseUrl());
    parcels.respond(401, "{}");

    CompletionFailure failure = catchThrowableOfType(() -> client.complete(parcelId), CompletionFailure.class);
    parcels.respond(200, delivered());
    client.complete(parcelId);

    assertFailure(failure, "BLOCKED", "PARCEL_REQUEST_REJECTED");
    assertThat(keycloak.requests()).isEqualTo(2);
    assertThat(parcels.requests()).isEqualTo(2);
  }

  @Test
  void connectionClosedWithoutReplyIsPendingAndThePatchIsNotRepeated() {
    parcels.closeWithoutReply();

    long start = System.nanoTime();
    CompletionFailure failure = fail(parcels.baseUrl());

    assertFailure(failure, "PENDING", "PARCEL_SERVICE_UNAVAILABLE");
    assertThat(elapsedMs(start)).isLessThan(2000);
    // The JDK client never repeats a PATCH on its own.
    assertThat(parcels.requests()).isEqualTo(1);
  }

  @Test
  void slowTokenEndpointLeavesTheDeliveryPendingWithoutCallingTheParcelService() {
    keycloak.respondAfter(Duration.ofMillis(6500), 200, TOKEN_BODY);

    long start = System.nanoTime();
    CompletionFailure failure = fail(parcels.baseUrl());

    assertFailure(failure, "PENDING", "TOKEN_SERVICE_UNAVAILABLE");
    assertThat(elapsedMs(start)).isBetween(4500L, 7000L);
    assertThat(keycloak.requests()).isEqualTo(1);
    assertThat(parcels.requests()).isZero();
  }

  @Test
  void tokenEndpointDownLeavesTheDeliveryPendingWithoutCallingTheParcelService() {
    ParcelStatusClient client = new ParcelStatusClient(RestClient.builder(),
        tokens("http://127.0.0.1:" + StubHttpServer.unusedPort() + "/token"), parcels.baseUrl());

    CompletionFailure failure = catchThrowableOfType(() -> client.complete(parcelId), CompletionFailure.class);

    assertFailure(failure, "PENDING", "TOKEN_SERVICE_UNAVAILABLE");
    assertThat(parcels.requests()).isZero();
  }

  @Test
  void slowResponseBodyLeavesTheDeliveryPending() {
    // Prompt headers must not let a slow response body bypass the five-second timeout.
    parcels.trickle(200, delivered(), 8, Duration.ofMillis(800));

    long start = System.nanoTime();
    CompletionFailure failure = fail(parcels.baseUrl());

    assertFailure(failure, "PENDING", "PARCEL_SERVICE_UNAVAILABLE");
    assertThat(elapsedMs(start)).isBetween(4500L, 7000L);
    assertThat(parcels.requests()).isEqualTo(1);
  }

  @Test
  void failuresDoNotLogTokensOrClientSecrets(CapturedOutput output) {
    parcels.respond(503, "{}");
    fail(parcels.baseUrl());
    parcels.respond(401, "{}");
    fail(parcels.baseUrl());
    fail("http://127.0.0.1:" + StubHttpServer.unusedPort());

    assertThat(output.getAll()).doesNotContain(SERVICE_TOKEN, CLIENT_SECRET);
  }

  private ParcelStatusClient client(String parcelBaseUrl) {
    return new ParcelStatusClient(RestClient.builder(), tokens(keycloak.baseUrl() + "/token"), parcelBaseUrl);
  }

  private static ServiceTokenProvider tokens(String tokenUri) {
    return new ServiceTokenProvider(RestClient.builder(), tokenUri, "dispatch-courier-service", CLIENT_SECRET);
  }

  private CompletionFailure fail(String parcelBaseUrl) {
    return catchThrowableOfType(() -> client(parcelBaseUrl).complete(parcelId), CompletionFailure.class);
  }

  private String delivered() {
    return "{\"parcelId\":\"" + parcelId + "\",\"status\":\"DELIVERED\",\"changed\":true}";
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
