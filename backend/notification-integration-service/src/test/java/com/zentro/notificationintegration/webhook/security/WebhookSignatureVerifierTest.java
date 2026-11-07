package com.zentro.notificationintegration.webhook.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zentro.notificationintegration.webhook.security.WebhookSignatureVerifier.Result;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every outcome of the reference verifier. The valid signatures below were computed independently with Python's
 * hmac module, so the tests do not only compare the signer with itself.
 */
class WebhookSignatureVerifierTest {
  private static final String SECRET = "whsec_test-secret";
  private static final long TS = 1762400000L;
  private static final String TS_HEADER = Long.toString(TS);
  private static final byte[] BODY = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
  /** HMAC-SHA256("whsec_test-secret", "1762400000.{\"eventId\":\"e1\"}"). */
  private static final String SIGNATURE = "sha256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da";
  private static final Instant AT_SIGNING = Instant.ofEpochSecond(TS);

  private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(new WebhookSigner());

  private Result verify(String timestamp, String signature, byte[] body, Instant now) {
    return verifier.verify(SECRET, timestamp, signature, body, now);
  }

  @Test
  @DisplayName("A request signed with the contract verifies")
  void valid() {
    assertThat(verify(TS_HEADER, SIGNATURE, BODY, AT_SIGNING)).isEqualTo(Result.VALID);
    assertThat(Result.VALID.isValid()).isTrue();
  }

  @Test
  @DisplayName("Non-ASCII bodies are verified over their UTF-8 bytes")
  void utf8Body() {
    byte[] body = "{\"city\":\"Kandy – ගාල්ල\"}".getBytes(StandardCharsets.UTF_8);
    String signature = "sha256=a3bf33c1022a128bbb26a7889552d412efaa4760190c545e56547fc341dbf166";

    assertThat(verify(TS_HEADER, signature, body, AT_SIGNING)).isEqualTo(Result.VALID);
    assertThat(verify(TS_HEADER, signature, "{\"city\":\"Kandy - ගාල්ල\"}".getBytes(StandardCharsets.UTF_8), AT_SIGNING))
        .isEqualTo(Result.SIGNATURE_MISMATCH);
  }

  @Test
  @DisplayName("An empty body is signed and verified like any other body")
  void emptyBody() {
    String signature = "sha256=310f4677704a59d37b82edea49b92d0034d5588a3207492ba7d91a0b4f81e785";

    assertThat(verify(TS_HEADER, signature, new byte[0], AT_SIGNING)).isEqualTo(Result.VALID);
    assertThat(verify(TS_HEADER, signature, null, AT_SIGNING)).isEqualTo(Result.VALID);
  }

  @ParameterizedTest(name = "body \"{0}\"")
  @ValueSource(strings = {
      "{\"eventId\":\"e2\"}",
      "{\"eventId\": \"e1\"}",
      "{\"eventId\":\"e1\"}\n",
      " {\"eventId\":\"e1\"}",
      "{\"eventId\":\"E1\"}",
      ""})
  @DisplayName("Any change to the body, even whitespace or a trailing newline, is a mismatch")
  void tamperedBody(String body) {
    assertThat(verify(TS_HEADER, SIGNATURE, body.getBytes(StandardCharsets.UTF_8), AT_SIGNING))
        .isEqualTo(Result.SIGNATURE_MISMATCH);
  }

  @Test
  @DisplayName("A different secret, or the secret without its whsec_ prefix, is a mismatch")
  void wrongSecret() {
    assertThat(verifier.verify("whsec_other-secret", TS_HEADER, SIGNATURE, BODY, AT_SIGNING))
        .isEqualTo(Result.SIGNATURE_MISMATCH);
    assertThat(verifier.verify("test-secret", TS_HEADER, SIGNATURE, BODY, AT_SIGNING))
        .isEqualTo(Result.SIGNATURE_MISMATCH);
  }

  @Test
  @DisplayName("Moving the timestamp to a new value breaks the signature, so it cannot be refreshed by an attacker")
  void timestampIsSigned() {
    String newer = Long.toString(TS + 120);

    assertThat(verify(newer, SIGNATURE, BODY, AT_SIGNING.plusSeconds(120))).isEqualTo(Result.SIGNATURE_MISMATCH);
  }

  @ParameterizedTest(name = "timestamp \"{0}\", signature \"{1}\"")
  @CsvSource(value = {
      "NULL, " + SIGNATURE,
      "'', " + SIGNATURE,
      "'   ', " + SIGNATURE,
      "1762400000, NULL",
      "1762400000, ''",
      "1762400000, '  '",
  }, nullValues = "NULL")
  @DisplayName("Missing or blank headers are MISSING_HEADER")
  void missingHeaders(String timestamp, String signature) {
    assertThat(verify(timestamp, signature, BODY, AT_SIGNING)).isEqualTo(Result.MISSING_HEADER);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {
      "47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da",
      "sha1=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da",
      "SHA256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da",
      "sha256=47BACC5F771F240148D5F141E54E320B6DE24EF4D162E25EEAAF792C5ED7C7DA",
      "sha256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7d",
      "sha256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da0",
      "sha256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7dz",
      "sha256= 47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da",
      "sha256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da ",
      "sha256=47bacc5f771f240148d5f141e54e320b6de24ef4d162e25eeaaf792c5ed7c7da,sha256=00",
  })
  @DisplayName("Anything but sha256= plus 64 lowercase hex characters is MALFORMED_SIGNATURE")
  void malformedSignature(String signature) {
    assertThat(verify(TS_HEADER, signature, BODY, AT_SIGNING)).isEqualTo(Result.MALFORMED_SIGNATURE);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"abc", "-1762400000", "1762400000.5", "1.7624E9", " 1762400000", "1762400000ms",
      "9999999999999", "0x690C9A00"})
  @DisplayName("Anything but whole unix seconds (at most 12 digits) is MALFORMED_TIMESTAMP")
  void malformedTimestamp(String timestamp) {
    assertThat(verify(timestamp, SIGNATURE, BODY, AT_SIGNING)).isEqualTo(Result.MALFORMED_TIMESTAMP);
  }

  @Test
  @DisplayName("Exactly 300 s old is accepted; 301 s is TIMESTAMP_TOO_OLD")
  void maxAgeBoundary() {
    assertThat(verify(TS_HEADER, SIGNATURE, BODY, AT_SIGNING.plusSeconds(300))).isEqualTo(Result.VALID);
    assertThat(verify(TS_HEADER, SIGNATURE, BODY, AT_SIGNING.plusSeconds(301))).isEqualTo(Result.TIMESTAMP_TOO_OLD);
    assertThat(verify(TS_HEADER, SIGNATURE, BODY, AT_SIGNING.plus(Duration.ofDays(1)))).isEqualTo(Result.TIMESTAMP_TOO_OLD);
  }

  @Test
  @DisplayName("Exactly 60 s ahead is accepted; 61 s is TIMESTAMP_IN_FUTURE")
  void maxFutureBoundary() {
    assertThat(verify(TS_HEADER, SIGNATURE, BODY, AT_SIGNING.minusSeconds(60))).isEqualTo(Result.VALID);
    assertThat(verify(TS_HEADER, SIGNATURE, BODY, AT_SIGNING.minusSeconds(61))).isEqualTo(Result.TIMESTAMP_IN_FUTURE);
  }

  @Test
  @DisplayName("A forged signature on a stale request reports the mismatch, not the age")
  void mismatchWinsOverAge() {
    String forged = "sha256=" + "0".repeat(64);

    assertThat(verify(TS_HEADER, forged, BODY, AT_SIGNING.plusSeconds(3600))).isEqualTo(Result.SIGNATURE_MISMATCH);
  }

  @Test
  @DisplayName("The tolerance window is configurable; zero means the exact second only")
  void customTolerance() {
    WebhookSignatureVerifier strict = new WebhookSignatureVerifier(new WebhookSigner(), Duration.ZERO, Duration.ZERO);

    assertThat(strict.verify(SECRET, TS_HEADER, SIGNATURE, BODY, AT_SIGNING)).isEqualTo(Result.VALID);
    assertThat(strict.verify(SECRET, TS_HEADER, SIGNATURE, BODY, AT_SIGNING.plusSeconds(1))).isEqualTo(Result.TIMESTAMP_TOO_OLD);
    assertThat(strict.verify(SECRET, TS_HEADER, SIGNATURE, BODY, AT_SIGNING.minusSeconds(1))).isEqualTo(Result.TIMESTAMP_IN_FUTURE);
  }

  @ParameterizedTest(name = "secret \"{0}\"")
  @NullAndEmptySource
  @ValueSource(strings = {"  "})
  @DisplayName("Verifying without a configured secret is a programming error, not a result")
  void secretRequired(String secret) {
    assertThatThrownBy(() -> verifier.verify(secret, TS_HEADER, SIGNATURE, BODY, AT_SIGNING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("A signing secret is required");
  }

  @Test
  @DisplayName("Negative tolerances are rejected when the verifier is created")
  void negativeTolerance() {
    assertThatThrownBy(() -> new WebhookSignatureVerifier(new WebhookSigner(), Duration.ofSeconds(-1), Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WebhookSignatureVerifier(new WebhookSigner(), Duration.ZERO, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Signatures produced by WebhookSigner for any timestamp verify (round trip)")
  void roundTrip() {
    WebhookSigner signer = new WebhookSigner();
    long now = 1_800_000_000L;
    String body = "{\"eventId\":\"rt\",\"payload\":{\"status\":\"DELIVERED\"}}";

    String signature = signer.sign("whsec_round-trip", now, body);

    assertThat(verifier.verify("whsec_round-trip", Long.toString(now), signature,
        body.getBytes(StandardCharsets.UTF_8), Instant.ofEpochSecond(now + 5))).isEqualTo(Result.VALID);
  }
}
