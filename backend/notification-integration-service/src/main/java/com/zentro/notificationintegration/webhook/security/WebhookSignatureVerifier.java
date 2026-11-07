package com.zentro.notificationintegration.webhook.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reference verifier for the merchant webhook signing contract (ZD-052). S5 does not call it at runtime: it is the
 * receiver-side counterpart of {@link WebhookSigner}, used by the tests to prove every outbound delivery verifies
 * and documented in docs/api/merchant-webhooks.md so merchants can copy it.
 *
 * <p>Contract: {@code X-SwiftDrop-Timestamp} is unix seconds; {@code X-SwiftDrop-Signature} is {@code sha256=} plus
 * 64 lowercase hex characters of HMAC-SHA256 over {@code "<timestamp>.<raw body>"} (UTF-8), keyed with the full
 * {@code whsec_...} secret. The HMAC is recomputed with {@link WebhookSigner} (there is exactly one signing
 * implementation), compared in constant time, and the timestamp must be within the tolerance window, which limits
 * replays. Duplicate deliveries inside the window are detected by the receiver with {@code X-SwiftDrop-Event-Id}.
 *
 * <p>Stateless and thread-safe; it never logs the secret, the signature or the body.
 */
public class WebhookSignatureVerifier {
  /** A signed request older than this is rejected (replay window). */
  public static final Duration DEFAULT_MAX_AGE = Duration.ofSeconds(300);
  /** A timestamp further in the future than this is rejected (clock skew allowance). */
  public static final Duration DEFAULT_MAX_FUTURE = Duration.ofSeconds(60);

  private static final Pattern SIGNATURE = Pattern.compile("^sha256=[0-9a-f]{64}$");
  private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{1,12}$");

  /** Outcome of one verification; only {@link #VALID} means the request may be processed. */
  public enum Result {
    VALID,
    /** The timestamp or signature header is missing or blank. */
    MISSING_HEADER,
    /** The signature header is not {@code sha256=} followed by 64 lowercase hex characters. */
    MALFORMED_SIGNATURE,
    /** The timestamp header is not a whole number of unix seconds. */
    MALFORMED_TIMESTAMP,
    /** The signature does not match the body and timestamp for this secret (tampered, wrong secret or forged). */
    SIGNATURE_MISMATCH,
    /** The signature is genuine but older than the allowed age (possible replay). */
    TIMESTAMP_TOO_OLD,
    /** The signature is genuine but the timestamp is too far in the future (clock skew or tampering). */
    TIMESTAMP_IN_FUTURE;

    public boolean isValid() {
      return this == VALID;
    }
  }

  private final WebhookSigner signer;
  private final Duration maxAge;
  private final Duration maxFuture;

  public WebhookSignatureVerifier(WebhookSigner signer) {
    this(signer, DEFAULT_MAX_AGE, DEFAULT_MAX_FUTURE);
  }

  public WebhookSignatureVerifier(WebhookSigner signer, Duration maxAge, Duration maxFuture) {
    this.signer = Objects.requireNonNull(signer, "signer");
    this.maxAge = requireNotNegative(maxAge, "maxAge");
    this.maxFuture = requireNotNegative(maxFuture, "maxFuture");
  }

  /**
   * Verifies one received request.
   *
   * @param secret          the merchant's signing secret ({@code whsec_...}); configuration, so it must be set
   * @param timestampHeader value of {@code X-SwiftDrop-Timestamp}
   * @param signatureHeader value of {@code X-SwiftDrop-Signature}
   * @param rawBody         the request body exactly as received, before any JSON parsing
   * @param now             the receiver's current time
   */
  public Result verify(String secret, String timestampHeader, String signatureHeader, byte[] rawBody, Instant now) {
    if (secret == null || secret.isBlank()) {
      throw new IllegalArgumentException("A signing secret is required");
    }
    Objects.requireNonNull(now, "now");
    if (timestampHeader == null || timestampHeader.isBlank() || signatureHeader == null || signatureHeader.isBlank()) {
      return Result.MISSING_HEADER;
    }
    if (!TIMESTAMP.matcher(timestampHeader).matches()) {
      return Result.MALFORMED_TIMESTAMP;
    }
    if (!SIGNATURE.matcher(signatureHeader).matches()) {
      return Result.MALFORMED_SIGNATURE;
    }

    long timestamp = Long.parseLong(timestampHeader);
    String body = new String(rawBody == null ? new byte[0] : rawBody, StandardCharsets.UTF_8);
    byte[] expected = signer.sign(secret, timestamp, body).getBytes(StandardCharsets.US_ASCII);
    byte[] received = signatureHeader.getBytes(StandardCharsets.US_ASCII);
    if (!MessageDigest.isEqual(expected, received)) {
      return Result.SIGNATURE_MISMATCH;
    }

    long ageSeconds = now.getEpochSecond() - timestamp;
    if (ageSeconds > maxAge.toSeconds()) {
      return Result.TIMESTAMP_TOO_OLD;
    }
    if (-ageSeconds > maxFuture.toSeconds()) {
      return Result.TIMESTAMP_IN_FUTURE;
    }
    return Result.VALID;
  }

  private static Duration requireNotNegative(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }
}
