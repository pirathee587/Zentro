package com.zentro.dispatchcourier.courier.service;

import static org.mockito.Mockito.*;

import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.exception.CompletionFailure;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProofCompletionWorkerTest {
  ProofRepository proofs = mock(ProofRepository.class);
  ParcelStatusClient parcels = mock(ParcelStatusClient.class);
  ProofCompletionWorker worker = new ProofCompletionWorker(proofs, parcels, 8);
  ProofRepository.Claim claim(int attempts) { return new ProofRepository.Claim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), attempts); }
  @Test void successAcknowledgedOnlyAfterS2() {
    var claim = claim(1); worker.process(claim);
    var order = inOrder(parcels, proofs);
    order.verify(parcels).complete(claim.parcelId()); order.verify(proofs).finish(claim, "COMPLETED", null, 5);
  }
  @Test void timeoutBacksOffAndEventuallyBlocks() {
    var first = claim(1); var last = claim(8);
    doThrow(new CompletionFailure("PENDING", "PARCEL_SERVICE_UNAVAILABLE")).when(parcels).complete(any());
    worker.process(first); worker.process(last);
    verify(proofs).finish(first, "PENDING", "PARCEL_SERVICE_UNAVAILABLE", 5);
    verify(proofs).finish(last, "BLOCKED", "RETRY_EXHAUSTED", 300);
  }
  @Test void businessAndConfigurationFailuresDoNotRetry() {
    var rejected = claim(1); var blocked = claim(1);
    doThrow(new CompletionFailure("REJECTED", "DELIVERY_TRANSITION_REJECTED")).when(parcels).complete(rejected.parcelId());
    doThrow(new CompletionFailure("BLOCKED", "SERVICE_AUTH_NOT_CONFIGURED")).when(parcels).complete(blocked.parcelId());
    worker.process(rejected); worker.process(blocked);
    verify(proofs).finish(rejected, "REJECTED", "DELIVERY_TRANSITION_REJECTED", 5);
    verify(proofs).finish(blocked, "BLOCKED", "SERVICE_AUTH_NOT_CONFIGURED", 5);
  }
  @Test void acknowledgmentFailureLeavesDurableClaimForLeaseRecovery() {
    var claim = claim(1);
    when(proofs.claim(8)).thenReturn(Optional.of(claim));
    doThrow(new org.springframework.dao.DataAccessResourceFailureException("offline")).when(proofs).finish(claim, "COMPLETED", null, 5);
    worker.completePending(); verify(parcels).complete(claim.parcelId());
    verify(proofs).claim(8);
  }
}

