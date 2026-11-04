package com.zentro.dispatchcourier;

import java.time.Instant;
import java.util.UUID;

public record ProofResponse(UUID proofId, UUID parcelId, String recipientName, String notes,
    String proofReference, Instant submittedAt, String completionState, Instant completedAt) {
  static ProofResponse from(ProofOfDelivery proof) {
    return new ProofResponse(proof.id(), proof.parcelId(), proof.recipientName(), proof.notes(),
        proof.proofReference(), proof.submittedAt(), proof.completionState(), proof.completedAt());
  }
  @Override public String toString() { return "ProofResponse[proofId=" + proofId + ", state=" + completionState + "]"; }
}
