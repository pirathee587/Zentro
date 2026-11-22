package com.zentro.dispatchcourier.pod;

import com.zentro.dispatchcourier.courier.service.ParcelStatusClient;
import com.zentro.dispatchcourier.exception.CompletionFailure;
import com.zentro.dispatchcourier.exception.ProofException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Explicit dispatcher recovery; deliberately not a scheduled/global POD worker. */
@RestController @RequestMapping("/api/v1/dispatch/proof-reviews") @PreAuthorize("hasRole('DISPATCHER')")
public class ProofCompletionController {
  private final JdbcTemplate jdbc; private final ParcelStatusClient parcels;
  public ProofCompletionController(JdbcTemplate jdbc, ParcelStatusClient parcels){this.jdbc=jdbc;this.parcels=parcels;}
  @PostMapping("/{reviewId}/complete") public ResponseEntity<java.util.Map<String,String>> complete(@PathVariable UUID reviewId,@AuthenticationPrincipal Jwt jwt) {
    int claimed=jdbc.update("UPDATE dispatch.proof_review SET decision='COMPLETION_PROCESSING',reviewed_by_subject=NULL,reviewed_at=NULL,updated_at=now(),version=version+1 WHERE id=? AND decision IN ('COMPLETION_PENDING','COMPLETION_FAILED','BLOCKED')",reviewId);
    if(claimed!=1) throw new ProofException(409,"COMPLETION_IN_PROGRESS","Completion is already processing or not ready.");
    UUID parcel=jdbc.query("SELECT p.parcel_id FROM dispatch.proof_review r JOIN dispatch.proof_revision v ON v.id=r.revision_id JOIN dispatch.proof_of_delivery p ON p.id=v.proof_id WHERE r.id=?",(x,n)->x.getObject(1,UUID.class),reviewId).stream().findFirst().orElseThrow(()->new ProofException(404,"PROOF_REVIEW_NOT_FOUND","Proof review not found."));
    try {
      parcels.complete(parcel);
      jdbc.update("INSERT INTO dispatch.proof_completion_attempt(id,review_id,requested_by_subject,outcome) VALUES(?,?,?,'CONFIRMED')",UUID.randomUUID(),reviewId,jwt.getSubject());
      int changed=jdbc.update("UPDATE dispatch.proof_review SET decision='COMPLETED',reviewed_by_subject=?,reviewed_at=now(),completion_error_code=NULL,updated_at=now(),version=version+1 WHERE id=? AND decision='COMPLETION_PROCESSING'",jwt.getSubject(),reviewId);
      if(changed==1) jdbc.update("INSERT INTO dispatch.proof_review_audit(id,review_id,from_decision,to_decision,actor_subject) VALUES(?,?,'COMPLETION_PROCESSING','COMPLETED',?)",UUID.randomUUID(),reviewId,jwt.getSubject());
      jdbc.update("UPDATE dispatch.proof_of_delivery SET completion_state='COMPLETED', completed_at=now() WHERE parcel_id=?", parcel);
      return ResponseEntity.ok(java.util.Map.of("status","COMPLETED"));
    } catch (CompletionFailure failure) {
      String next="PENDING".equals(failure.state())?"COMPLETION_FAILED":"BLOCKED";
      String outcome="PENDING".equals(failure.state())?"RETRYABLE_FAILURE":"BLOCKED_FAILURE";
      jdbc.update("INSERT INTO dispatch.proof_completion_attempt(id,review_id,requested_by_subject,outcome,error_code) VALUES(?,?,?,?,?)",UUID.randomUUID(),reviewId,jwt.getSubject(),outcome,failure.code());
      jdbc.update("UPDATE dispatch.proof_review SET decision=?,reviewed_by_subject=?,reviewed_at=now(),completion_error_code=?,updated_at=now(),version=version+1 WHERE id=? AND decision='COMPLETION_PROCESSING'",next,jwt.getSubject(),failure.code(),reviewId);
      throw new ProofException(409,"COMPLETION_FAILED","Parcel completion was not confirmed.");
    }
  }
  /** Recovery reconciles S2 state only; it deliberately never repeats the completion RPC. */
  @PostMapping("/{reviewId}/recover") public ResponseEntity<java.util.Map<String,String>> recover(@PathVariable UUID reviewId,@AuthenticationPrincipal Jwt jwt) {
    UUID parcel=jdbc.query("SELECT p.parcel_id FROM dispatch.proof_review r JOIN dispatch.proof_revision v ON v.id=r.revision_id JOIN dispatch.proof_of_delivery p ON p.id=v.proof_id WHERE r.id=? AND r.decision='COMPLETION_PROCESSING' AND r.updated_at < now()-interval '5 minutes' AND p.id <> 'a4f5e154-0bea-4df3-b04b-7df25aecc0a1'::uuid",(x,n)->x.getObject(1,UUID.class),reviewId).stream().findFirst().orElseThrow(()->new ProofException(409,"RECOVERY_NOT_READY","Completion is not stale or cannot be recovered."));
    String status;
    try { status=parcels.getParcel(parcel).getStatus(); }
    catch (RuntimeException unavailable) { jdbc.update("UPDATE dispatch.proof_review SET decision='BLOCKED',reviewed_by_subject=?,reviewed_at=now(),completion_error_code='RECOVERY_STATUS_UNCERTAIN',updated_at=now(),version=version+1 WHERE id=? AND decision='COMPLETION_PROCESSING'",jwt.getSubject(),reviewId); jdbc.update("INSERT INTO dispatch.proof_completion_attempt(id,review_id,requested_by_subject,outcome,error_code) VALUES(?,?,?,?,?)",UUID.randomUUID(),reviewId,jwt.getSubject(),"BLOCKED_FAILURE","RECOVERY_STATUS_UNCERTAIN"); throw new ProofException(503,"RECOVERY_STATUS_UNCERTAIN","Parcel status could not be confirmed; no completion was replayed."); }
    if("DELIVERED".equals(status)) { jdbc.update("UPDATE dispatch.proof_review SET decision='COMPLETED',reviewed_by_subject=?,reviewed_at=now(),completion_error_code=NULL,updated_at=now(),version=version+1 WHERE id=? AND decision='COMPLETION_PROCESSING'",jwt.getSubject(),reviewId); jdbc.update("INSERT INTO dispatch.proof_completion_attempt(id,review_id,requested_by_subject,outcome) VALUES(?,?,?,'CONFIRMED')",UUID.randomUUID(),reviewId,jwt.getSubject()); jdbc.update("UPDATE dispatch.proof_of_delivery SET completion_state='COMPLETED', completed_at=now() WHERE parcel_id=?", parcel); return ResponseEntity.ok(java.util.Map.of("status","COMPLETED")); }
    jdbc.update("UPDATE dispatch.proof_review SET decision='BLOCKED',reviewed_by_subject=?,reviewed_at=now(),completion_error_code='RECOVERY_REQUIRES_REVIEW',updated_at=now(),version=version+1 WHERE id=? AND decision='COMPLETION_PROCESSING'",jwt.getSubject(),reviewId);
    jdbc.update("INSERT INTO dispatch.proof_completion_attempt(id,review_id,requested_by_subject,outcome,error_code) VALUES(?,?,?,?,?)",UUID.randomUUID(),reviewId,jwt.getSubject(),"BLOCKED_FAILURE","RECOVERY_REQUIRES_REVIEW");
    return ResponseEntity.ok(java.util.Map.of("status","BLOCKED"));
  }
}
