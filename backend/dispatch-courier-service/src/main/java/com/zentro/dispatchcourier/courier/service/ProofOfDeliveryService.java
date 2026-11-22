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
  private final ParcelStatusClient parcelStatusClient;

  public ProofOfDeliveryService(IdentityCourierClient identity, AssignmentRepository assignments, ProofRepository proofs) {
    this.identity = identity;
    this.assignments = assignments;
    this.proofs = proofs;
    this.parcelStatusClient = null;
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ProofOfDeliveryService(IdentityCourierClient identity, AssignmentRepository assignments,
      ProofRepository proofs, org.springframework.beans.factory.ObjectProvider<ParcelStatusClient> parcelStatusClient) {
    this.identity = identity;
    this.assignments = assignments;
    this.proofs = proofs;
    this.parcelStatusClient = parcelStatusClient.getIfAvailable();
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
    var parcel = parcelStatusClient == null ? null : parcelStatusClient.getParcel(parcelId);
    return new ProofRepository.DeliverySummary(parcelId, assignment.courierId(), assignment.assignedAt(),
        proof == null ? null : proof.id(), proof == null ? null : proof.completionState(),
        assignment.assignmentStatus(), assignment.respondedAt(), assignment.rejectionReason(),
        parcel == null ? null : parcel.getTrackingNumber(), parcel == null ? null : parcel.getStatus());
  }
  public ProofResponse proof(UUID parcelId, Jwt jwt) {
    owned(parcelId, courier(jwt));
    return ProofResponse.from(proofs.find(parcelId)
        .orElseThrow(() -> new ProofException(404, "PROOF_NOT_FOUND", "No proof has been recorded yet.")));
  }
  public ProofRepository.Submission submit(UUID parcelId, Jwt jwt, SubmitProofRequest request) {
    var courier = courier(jwt);
    var assignment = owned(parcelId, courier);
    if ("REJECTED".equals(assignment.assignmentStatus()))
      throw new ProofException(409, "ASSIGNMENT_REJECTED", "Cannot submit proof for a rejected assignment.");
    if (request == null) throw new ProofException(400, "INVALID_PROOF", "Proof fields are required.");
    return proofs.saveOwned(parcelId, courier.id(), courier.userId(), jwt.getSubject(), request.normalized());
  }
  public void verifyOwnership(UUID parcelId, Jwt jwt) { owned(parcelId, courier(jwt)); }

  public AssignmentRepository.Assignment accept(UUID parcelId, Jwt jwt) {
    var courier = courier(jwt);
    return assignments.accept(parcelId, courier.id(), courier.userId(), jwt.getSubject());
  }

  public AssignmentRepository.Assignment reject(UUID parcelId, Jwt jwt, String reason) {
    var courier = courier(jwt);
    return assignments.reject(parcelId, courier.id(), courier.userId(), reason, jwt.getSubject());
  }

  public java.util.Map<?, ?> updateStatus(UUID parcelId, Jwt jwt, String status, String reason, String note) {
    var courier = courier(jwt);
    var assignment = owned(parcelId, courier);
    if (!"ACCEPTED".equals(assignment.assignmentStatus())) {
      throw new ProofException(409, "ASSIGNMENT_NOT_ACCEPTED",
          "Accept the delivery assignment before updating parcel status.");
    }
    if (parcelStatusClient == null) {
      throw new ProofException(503, "PARCEL_STATUS_UNAVAILABLE", "Parcel status client is not configured.");
    }
    return parcelStatusClient.updateCourierStatus(parcelId, status, reason, note, jwt.getSubject());
  }
}
