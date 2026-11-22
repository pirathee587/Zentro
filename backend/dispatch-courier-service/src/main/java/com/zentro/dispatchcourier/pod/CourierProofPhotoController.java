package com.zentro.dispatchcourier.pod;

import com.zentro.dispatchcourier.courier.dto.ProofResponse;
import com.zentro.dispatchcourier.courier.dto.SubmitProofRequest;
import com.zentro.dispatchcourier.courier.service.ProofOfDeliveryService;
import com.zentro.dispatchcourier.exception.ProofException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Photo proof creates no delivery proof until private storage upload succeeded. */
@RestController @RequestMapping("/api/v1/courier/deliveries") @PreAuthorize("hasRole('COURIER')")
public class CourierProofPhotoController {
  private final ProofOfDeliveryService proofs; private final ProofStageRepository stages; private final ProofRevisionRepository revisions; private final SupabaseProofStorage storage;
  public CourierProofPhotoController(ProofOfDeliveryService proofs,ProofStageRepository stages,ProofRevisionRepository revisions,SupabaseProofStorage storage){this.proofs=proofs;this.stages=stages;this.revisions=revisions;this.storage=storage;}
  @PostMapping(value="/{parcelId}/proof-photo",consumes="multipart/form-data")
  public ResponseEntity<ProofResponse> submit(@PathVariable UUID parcelId,@RequestHeader("Idempotency-Key") UUID key,@AuthenticationPrincipal Jwt jwt,
      @RequestPart String recipientName,@RequestPart(required=false) String notes,@RequestPart String proofReference,@RequestPart MultipartFile photo){
    proofs.verifyOwnership(parcelId,jwt); String type=ProofImageValidator.validate(photo); String hash=hash(photo); String path="proofs/staged/"+parcelId+"/"+key;
    var stage=stages.begin(parcelId,jwt.getSubject(),key,path,type,photo.getSize(),hash);
    if ("FINALIZED".equals(stage.state()) && stage.proofId()!=null) return ResponseEntity.ok(proofs.proof(parcelId,jwt));
    if("UPLOADING".equals(stage.state())) { storage.uploadToPath(path,photo,type); stages.uploaded(stage.id()); }
    var submission=proofs.submit(parcelId,jwt,new SubmitProofRequest(recipientName,notes,proofReference));
    int revision=revisions.next(submission.proof().id()); revisions.save(submission.proof().id(),revision,path,type,photo,jwt.getSubject()); stages.finalize(stage.id(), submission.proof().id());
    return ResponseEntity.status(submission.created()?201:200).body(ProofResponse.from(submission.proof()));
  }
  @GetMapping("/{parcelId}/proof-photo/status") public java.util.Map<String,Object> status(@PathVariable UUID parcelId,@AuthenticationPrincipal Jwt jwt){ proofs.verifyOwnership(parcelId,jwt); return java.util.Map.of("parcelId",parcelId,"staged",true); }
  private static String hash(MultipartFile file){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.getBytes()));}catch(Exception e){throw new ProofException(400,"INVALID_PROOF_IMAGE","Check the delivery photo.");}}
}
