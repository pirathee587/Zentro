package com.zentro.dispatchcourier.pod;

import com.zentro.dispatchcourier.exception.ProofException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

/** Dispatcher-only decisions. A decision never changes S2 directly; completion is a separate confirmed step. */
@RestController @RequestMapping("/api/v1/dispatch/proof-reviews") @PreAuthorize("hasRole('DISPATCHER')")
public class DispatcherProofReviewController {
  record Review(UUID id, UUID revisionId, UUID proofId, UUID parcelId, String decision, int version,
      String recipientName, String notes, String proofReference, String courierName, Instant submittedAt,
      String rejectionReason, String completionErrorCode) {}
  record Decision(String reason, int version) {}
  private final JdbcTemplate jdbc;
  private final SupabaseProofStorage storage;
  public DispatcherProofReviewController(JdbcTemplate jdbc, SupabaseProofStorage storage){this.jdbc=jdbc;this.storage=storage;}
  @GetMapping public List<Review> list(@RequestParam(defaultValue="PENDING_REVIEW") String state){
    return jdbc.query("SELECT r.id,r.revision_id,p.id,p.parcel_id,r.decision,r.version,p.recipient_name,p.notes,p.proof_reference,a.courier_name,v.submitted_at,r.rejection_reason,r.completion_error_code FROM dispatch.proof_review r JOIN dispatch.proof_revision v ON v.id=r.revision_id JOIN dispatch.proof_of_delivery p ON p.id=v.proof_id LEFT JOIN dispatch.delivery_assignment a ON a.parcel_id=p.parcel_id WHERE r.decision=? ORDER BY v.submitted_at",this::review,state);
  }
  @GetMapping("/{id}/photo-url") public java.util.Map<String,String> photo(@PathVariable UUID id) {
    String path=jdbc.query("SELECT v.object_path FROM dispatch.proof_review r JOIN dispatch.proof_revision v ON v.id=r.revision_id WHERE r.id=?",(x,n)->x.getString(1),id).stream().findFirst().orElseThrow(()->new ProofException(404,"PROOF_REVIEW_NOT_FOUND","Proof review not found."));
    return java.util.Map.of("url",storage.signedUrl(path),"expiresInSeconds","300");
  }
  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/{id}/approve") public ResponseEntity<Review> approve(@PathVariable UUID id,@RequestBody Decision body,@AuthenticationPrincipal Jwt jwt){return ResponseEntity.ok(decide(id,"COMPLETION_PENDING",null,body,jwt));}
  @org.springframework.transaction.annotation.Transactional
  @PostMapping("/{id}/reject") public ResponseEntity<Review> reject(@PathVariable UUID id,@RequestBody Decision body,@AuthenticationPrincipal Jwt jwt){
    if(body==null||body.reason()==null||body.reason().trim().isEmpty()) throw new ProofException(400,"REJECTION_REASON_REQUIRED","A rejection reason is required.");
    return ResponseEntity.ok(decide(id,"REJECTED",body.reason().trim(),body,jwt));
  }
  private Review decide(UUID id,String target,String reason,Decision body,Jwt jwt){
    if(body==null||body.version()<0) throw new ProofException(400,"INVALID_REVIEW_VERSION","A review version is required.");
    int changed=jdbc.update("UPDATE dispatch.proof_review SET decision=?,rejection_reason=?,reviewed_by_subject=?,reviewed_at=now(),updated_at=now(),version=version+1 WHERE id=? AND decision='PENDING_REVIEW' AND version=?",target,reason,jwt.getSubject(),id,body.version());
    if(changed!=1) throw new ProofException(409,"REVIEW_CONFLICT","This proof was already reviewed. Refresh the queue.");
    jdbc.update("INSERT INTO dispatch.proof_review_audit(id,review_id,from_decision,to_decision,actor_subject,reason) VALUES(?,?,'PENDING_REVIEW',?,?,?)",UUID.randomUUID(),id,target,jwt.getSubject(),reason);
    return jdbc.query("SELECT r.id,r.revision_id,p.id,p.parcel_id,r.decision,r.version,p.recipient_name,p.notes,p.proof_reference,a.courier_name,v.submitted_at,r.rejection_reason,r.completion_error_code FROM dispatch.proof_review r JOIN dispatch.proof_revision v ON v.id=r.revision_id JOIN dispatch.proof_of_delivery p ON p.id=v.proof_id LEFT JOIN dispatch.delivery_assignment a ON a.parcel_id=p.parcel_id WHERE r.id=?",this::review,id).stream().findFirst().orElseThrow();
  }
  private Review review(java.sql.ResultSet x,int n) throws java.sql.SQLException { return new Review(x.getObject(1,UUID.class),x.getObject(2,UUID.class),x.getObject(3,UUID.class),x.getObject(4,UUID.class),x.getString(5),x.getInt(6),x.getString(7),x.getString(8),x.getString(9),x.getString(10),x.getObject(11,java.time.OffsetDateTime.class).toInstant(),x.getString(12),x.getString(13)); }
}
