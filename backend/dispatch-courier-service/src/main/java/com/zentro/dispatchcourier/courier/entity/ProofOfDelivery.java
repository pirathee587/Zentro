package com.zentro.dispatchcourier.courier.entity;

import com.zentro.dispatchcourier.courier.dto.SubmitProofRequest;
import java.time.Instant;
import java.util.UUID;

public record ProofOfDelivery(UUID id, UUID parcelId, String recipientName, String notes,
    String proofReference, String submittedBySubject, Instant submittedAt, String completionState,
    Instant completedAt) {
  public boolean sameEvidence(SubmitProofRequest request) {
    return java.util.Objects.equals(recipientName, request.recipientName())
        && java.util.Objects.equals(notes, request.notes())
        && java.util.Objects.equals(proofReference, request.proofReference());
  }
  // Avoid accidental evidence disclosure in logging/debug output.
  @Override public String toString() { return "ProofOfDelivery[id=" + id + ", state=" + completionState + "]"; }
}
