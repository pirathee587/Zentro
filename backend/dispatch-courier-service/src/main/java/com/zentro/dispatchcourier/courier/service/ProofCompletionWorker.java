package com.zentro.dispatchcourier.courier.service;

import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.exception.CompletionFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "zentro.pod", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class ProofCompletionWorker {
  private static final Logger log = LoggerFactory.getLogger(ProofCompletionWorker.class);
  private final ProofRepository proofs;
  private final ParcelStatusClient parcels;
  private final int maxAttempts;
  public ProofCompletionWorker(ProofRepository proofs, ParcelStatusClient parcels,
      @Value("${zentro.pod.max-attempts:8}") int maxAttempts) {
    if (maxAttempts < 1 || maxAttempts > 20) throw new IllegalArgumentException("POD max-attempts must be 1..20");
    this.proofs = proofs;
    this.parcels = parcels;
    this.maxAttempts = maxAttempts;
  }
  @Scheduled(fixedDelayString = "${zentro.pod.poll-interval-ms:2000}")
  public void completePending() {
    try {
      for (int index = 0; index < 10; index++) {
        var claim = proofs.claim(maxAttempts);
        if (claim.isEmpty()) return;
        process(claim.get());
      }
    } catch (RuntimeException error) {
      // Lease expiry recovers a claim if its acknowledgment failed. No dependency body or evidence is logged.
      log.warn("POD completion round unavailable ({})", error.getClass().getSimpleName());
    }
  }
  public void process(ProofRepository.Claim claim) {
    String state = "COMPLETED";
    String code = null;
    try { parcels.complete(claim.parcelId()); }
    catch (CompletionFailure error) { state = error.state(); code = error.code(); }
    if ("PENDING".equals(state) && claim.attempts() >= maxAttempts) {
      state = "BLOCKED";
      code = "RETRY_EXHAUSTED";
    }
    long delay = Math.min(300, 5L * (1L << Math.min(claim.attempts() - 1, 6)));
    proofs.finish(claim, state, code, delay);
    if ("BLOCKED".equals(state) || "REJECTED".equals(state))
      log.warn("POD completion requires attention: proofId={} state={} code={}", claim.proofId(), state, code);
  }
}
