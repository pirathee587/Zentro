package com.zentro.dispatchcourier.courier.service;

import com.zentro.dispatchcourier.courier.dto.ProofResponse;
import com.zentro.dispatchcourier.courier.dto.SubmitProofRequest;
import com.zentro.dispatchcourier.courier.repository.ProofRepository;
import com.zentro.dispatchcourier.dispatch.repository.AssignmentRepository;
import com.zentro.dispatchcourier.exception.ProofException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

@Service
public class ProofOfDeliveryService {
  private final IdentityCourierClient identity;
  private final AssignmentRepository assignments;
  private final ProofRepository proofs;
  public ProofOfDeliveryService(IdentityCourierClient identity, AssignmentRepository assignments, ProofRepository proofs) {
    this.identity = identity;
    this.assignments = assignments;
    this.proofs = proofs;
  }
  private IdentityCourierClient.Courier courier(Jwt jwt) {
    if (jwt == null || jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getSubject().length() > 255)
      throw new ProofException(401, "INVALID_SUBJECT", "A valid courier session is required.");
    return identity.currentCourier(jwt.getTokenValue());
  }
  private AssignmentRepository.Assignment owned(UUID parcelId, IdentityCourierClient.Courier courier) {
    var assignment = assignments.find(parcelId)
        .orElseThrow(() -> new ProofException(404, "ASSIGNMENT_NOT_FOUND", "Delivery assignment not found."));
    if (!assignment.courierId().equals(courier.id()) || !assignment.courierUserId().equals(courier.userId()))
      throw new ProofException(403, "DELIVERY_FORBIDDEN", "This delivery is not assigned to you.");
    return assignment;
  }
  public List<ProofRepository.DeliverySummary> deliveries(Jwt jwt) {
    var courier = courier(jwt);
    return proofs.deliveries(courier.id(), courier.userId());
  }
  public ProofRepository.DeliverySummary delivery(UUID parcelId, Jwt jwt) {
    var assignment = owned(parcelId, courier(jwt));
    var proof = proofs.find(parcelId).orElse(null);
    return new ProofRepository.DeliverySummary(parcelId, assignment.courierId(), assignment.assignedAt(),
        proof == null ? null : proof.id(), proof == null ? null : proof.completionState());
  }
  public ProofResponse proof(UUID parcelId, Jwt jwt) {
    owned(parcelId, courier(jwt));
    return ProofResponse.from(proofs.find(parcelId)
        .orElseThrow(() -> new ProofException(404, "PROOF_NOT_FOUND", "No proof has been recorded yet.")));
  }
  public ProofRepository.Submission submit(UUID parcelId, Jwt jwt, SubmitProofRequest request) {
    var courier = courier(jwt);
    owned(parcelId, courier);
    if (request == null) throw new ProofException(400, "INVALID_PROOF", "Proof fields are required.");
    return proofs.saveOwned(parcelId, courier.id(), courier.userId(), jwt.getSubject(), request.normalized());
  }
}
