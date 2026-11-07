package com.zentro.notificationintegration.webhook.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.zentro.notificationintegration.webhook.security.WebhookSignatureVerifier.Result;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Replay protection as documented for receivers (ZD-052, D2): the signed timestamp limits how long a captured request
 * can be replayed, and inside that window the receiver de-duplicates on {@code X-SwiftDrop-Event-Id}. The receiver
 * below is the minimal example from docs/api/merchant-webhooks.md.
 */
class WebhookReplayProtectionTest {
  private static final String SECRET = "whsec_replay-test-secret";
  private static final Instant T0 = Instant.parse("2026-11-07T10:00:00Z");
  private static final String EVENT_ID = "6b0c1f8e-2d1a-4a4e-9f61-0d2b9c3e7a10";
  private static final String BODY = "{\"eventId\":\"" + EVENT_ID + "\",\"eventType\":\"parcel.status.updated\"}";

  private final WebhookSigner signer = new WebhookSigner();

  /** A request as S5 sends it: the event id, the attempt's timestamp and the signature over "<ts>.<body>". */
  record Delivery(String eventId, String timestamp, String signature, byte[] body) {}

  private Delivery signedAt(Instant at, String eventId, String body) {
    long ts = at.getEpochSecond();
    return new Delivery(eventId, Long.toString(ts), signer.sign(SECRET, ts, body), body.getBytes(StandardCharsets.UTF_8));
  }

  enum Outcome { ACCEPTED, DUPLICATE, REJECTED }

  /**
   * Minimal receiver: verify first (so forged requests cannot mark an event as seen), then accept each event id once.
   * A real receiver stores the seen ids durably for at least the timestamp window.
   */
  static final class ExampleReceiver {
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(new WebhookSigner());
    private final Set<String> seenEventIds = ConcurrentHashMap.newKeySet();
    final Map<Outcome, Integer> counts = new ConcurrentHashMap<>();
    Result lastRejection;

    Outcome handle(Delivery delivery, Instant now) {
      Result result = verifier.verify(SECRET, delivery.timestamp(), delivery.signature(), delivery.body(), now);
      Outcome outcome;
      if (!result.isValid() || delivery.eventId() == null || delivery.eventId().isBlank()) {
        lastRejection = result;
        outcome = Outcome.REJECTED;
      } else {
        outcome = seenEventIds.add(delivery.eventId()) ? Outcome.ACCEPTED : Outcome.DUPLICATE;
      }
      counts.merge(outcome, 1, Integer::sum);
      return outcome;
    }
  }

  @Test
  @DisplayName("A captured request replayed within the window is detected as a duplicate by its event id")
  void replayInsideWindowIsDuplicate() {
    ExampleReceiver receiver = new ExampleReceiver();
    Delivery captured = signedAt(T0, EVENT_ID, BODY);

    assertThat(receiver.handle(captured, T0.plusSeconds(1))).isEqualTo(Outcome.ACCEPTED);
    assertThat(receiver.handle(captured, T0.plusSeconds(120))).isEqualTo(Outcome.DUPLICATE);
    assertThat(receiver.counts).containsEntry(Outcome.ACCEPTED, 1).containsEntry(Outcome.DUPLICATE, 1);
  }

  @Test
  @DisplayName("A captured request replayed after the window is rejected even by a receiver that forgot the event id")
  void replayAfterWindowIsRejected() {
    Delivery captured = signedAt(T0, EVENT_ID, BODY);
    ExampleReceiver restarted = new ExampleReceiver();

    assertThat(restarted.handle(captured, T0.plusSeconds(301))).isEqualTo(Outcome.REJECTED);
    assertThat(restarted.lastRejection).isEqualTo(Result.TIMESTAMP_TOO_OLD);
  }

  @Test
  @DisplayName("An attacker cannot refresh the timestamp of a captured request: the signature no longer matches")
  void refreshedTimestampIsRejected() {
    ExampleReceiver receiver = new ExampleReceiver();
    Delivery captured = signedAt(T0, EVENT_ID, BODY);
    Delivery refreshed = new Delivery(captured.eventId(), Long.toString(T0.plusSeconds(600).getEpochSecond()),
        captured.signature(), captured.body());

    assertThat(receiver.handle(refreshed, T0.plusSeconds(600))).isEqualTo(Outcome.REJECTED);
    assertThat(receiver.lastRejection).isEqualTo(Result.SIGNATURE_MISMATCH);
  }

  @Test
  @DisplayName("A genuine retry of the same event (new timestamp and signature) is recognised as already processed")
  void retryOfProcessedEventIsDuplicate() {
    ExampleReceiver receiver = new ExampleReceiver();

    assertThat(receiver.handle(signedAt(T0, EVENT_ID, BODY), T0)).isEqualTo(Outcome.ACCEPTED);
    Delivery retry = signedAt(T0.plusSeconds(40), EVENT_ID, BODY);
    assertThat(retry.signature()).isNotEqualTo(signedAt(T0, EVENT_ID, BODY).signature());
    assertThat(receiver.handle(retry, T0.plusSeconds(41))).isEqualTo(Outcome.DUPLICATE);
  }

  @Test
  @DisplayName("A forged request does not mark the event as seen, so the genuine delivery is still accepted")
  void forgedRequestDoesNotPoisonSeenIds() {
    ExampleReceiver receiver = new ExampleReceiver();
    Delivery forged = new Delivery(EVENT_ID, Long.toString(T0.getEpochSecond()), "sha256=" + "a".repeat(64),
        BODY.getBytes(StandardCharsets.UTF_8));

    assertThat(receiver.handle(forged, T0)).isEqualTo(Outcome.REJECTED);
    assertThat(receiver.handle(signedAt(T0, EVENT_ID, BODY), T0)).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("Different events are each accepted once")
  void differentEventsAccepted() {
    ExampleReceiver receiver = new ExampleReceiver();
    String otherId = "11111111-2222-4333-8444-555555555555";

    assertThat(receiver.handle(signedAt(T0, EVENT_ID, BODY), T0)).isEqualTo(Outcome.ACCEPTED);
    assertThat(receiver.handle(signedAt(T0, otherId, BODY.replace(EVENT_ID, otherId)), T0)).isEqualTo(Outcome.ACCEPTED);
  }

  @Test
  @DisplayName("The same delivery arriving twice at the same moment is accepted exactly once")
  void concurrentDuplicates() throws Exception {
    ExampleReceiver receiver = new ExampleReceiver();
    Delivery delivery = signedAt(T0, EVENT_ID, BODY);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<Outcome>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        Callable<Outcome> call = () -> {
          start.await();
          return receiver.handle(delivery, T0.plusSeconds(2));
        };
        results.add(pool.submit(call));
      }
      start.countDown();
      List<Outcome> outcomes = new ArrayList<>();
      for (Future<Outcome> result : results) {
        outcomes.add(result.get());
      }
      assertThat(outcomes).containsOnlyOnce(Outcome.ACCEPTED);
      assertThat(outcomes).filteredOn(o -> o == Outcome.DUPLICATE).hasSize(7);
    } finally {
      pool.shutdownNow();
    }
  }
}
